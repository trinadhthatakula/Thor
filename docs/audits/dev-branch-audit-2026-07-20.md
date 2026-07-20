# Thor — `dev` Branch Codebase Audit

**Date:** 2026-07-20  **Branch:** `dev`  **Scope:** whole codebase (`:app` 176 files, `:suCore` 30, `:bypass` 3, `:vm-runtime` 2 — ~31K LOC Kotlin)
**Focus:** memory/resource leaks · anti-patterns · improvement opportunities

## Methodology

Fanned out **14 audit clusters** (12 bounded subsystem slices + 2 cross-cutting sweeps for leaks and coroutines/flow). Each auditor read its files in full, covered all three dimensions, and validated every suspected anti-pattern against the **android-agent-brain** ledger (83 codes / 13 categories) via `check_anti_patterns`, citing the matched `AP-*` code. Every finding was then handed to an **independent adversarial verifier** that re-opened the *current* source and tried to refute it — this filters false positives and anything already fixed (e.g. the earlier B1–B7 smoothness fixes).

- **92 agents · 3.1M tokens · 78 raw findings → 66 verified** (38 `CONFIRMED`, 28 `PLAUSIBLE`), **12 rejected** (listed at the end for transparency).
- Verdict legend: **`{C}` = CONFIRMED** (concrete failure demonstrated) · **`{P}` = PLAUSIBLE** (real, impact not fully proven).

### Severity distribution
| Severity | Count | Notes |
|---|---|---|
| High | 3 | crash / deadlock risks |
| Medium | 24 | leaks, binder-thread blocking, contract violations with real impact |
| Low | 39 | testability/architecture debt, minor perf, dead code, one-frame flickers |

### Headline themes (systemic root-causes)
1. **App-scoped `InstallerEventBus` (`@Single`, `replay=1`)** — a single design choice produces **5 findings**: a process-lifetime **Bitmap leak**, stale-state replay that **suppresses auto-parse**, cross-screen state bleed, and a **spurious network refresh**.
2. **One-off events carried in `StateFlow` `UiState`** instead of `SharedFlow` — **6+ screens**; violates the project's own contract, causes replayed/lost toasts.
3. **Hardcoded `Dispatchers.IO`** in ViewModels & use cases — **no central Koin dispatcher binding**; blocks deterministic unit testing.
4. **Domain-purity violations (`AP-A03`/`AP-A04`)** — `android.*` in domain use cases/models, `Context`/`PackageManager` in ViewModels.
5. **Fire-and-forget / unstructured coroutines** in hot paths, providers, and `Application.onCreate`.

---

## P0 — High priority (fix first)

### H1 · `rememberSaveable<List<AppInfo>>` crashes on state-save (multi-select + rotation) — `{C}` bug
`presentation/widgets/AppList.kt:125`
`var multiSelection by rememberSaveable { mutableStateOf(emptyList<AppInfo>()) }` uses the default autoSaver, but `AppInfo` is only `@Serializable`/`@Immutable` — **not `Parcelable`/`java.io.Serializable`**. Empty list saves fine, so it's invisible until populated.
**Failure:** enter multi-select (≥1 app) → rotate / background → `onSaveInstanceState` tries to marshal the `ArrayList<AppInfo>` → `NotSerializableException` crash.
**Fix:** keep `multiSelection` in plain `remember` (it's already cleared on `appListType` change & `BackHandler`), or `rememberSaveable` only the stable `Set<String>` of package names and re-resolve.

### H2 · Root bind can suspend forever holding `connectionMutex` — `{C}` thread-safety
`data/gateway/RootSystemGateway.kt:75`
The whole bind handshake (`suspendCancellableCoroutine { RootService.bind(...) }`) runs **inside `connectionMutex.withLock`**, and the continuation is resumed **only** from `onServiceConnected`. There is no bind timeout and `onNullBinding`/`isRootImpossible` are unhandled.
**Failure:** binder returns null or the su daemon dies/is revoked mid-session (e.g. the init-time `pkill -f <pkg>:root` at L46–52 races the bind) → continuation never resumes → the mutex is pinned → **every subsequent privileged op deadlocks**.
**Fix:** wrap the bind in `withTimeoutOrNull(...)`, resume from an `onNullBinding` override, unbind the stale connection on timeout/null, and avoid holding `connectionMutex` across the suspend.

### H3 · `ExtensionManagerViewModel.loadExtensions()` has zero error handling around PM IPC — `{C}` `AP-K05`
`presentation/extension/ExtensionManagerViewModel.kt:42`
Launched from `init{}` as `viewModelScope.launch(Dispatchers.IO) { extensionManager.loadExtensions() ... }` with no `runCatching`. `ExtensionManager.loadExtensions()`/`getExtensionPackageName()` call `pm.getInstalledApplications(GET_META_DATA)` unguarded.
**Failure:** on a device with many apps, that binder call throws `TransactionTooLargeException`/`DeadObjectException` → uncaught → **app crashes the moment the Extensions screen opens**.
**Fix:** wrap in `runCatching`/`CoroutineExceptionHandler`, add `error: String?` to `ExtensionUiState`, set `isLoading=false` and show a retry.

---

## P1 — Memory & resource leaks

### L1 · `InstallerEventBus` (`@Single`, `replay=1`) — one design flaw, 5 symptoms — `{C}`/`{P}`
`domain/InstallerEventBus.kt:14-16` (+ `AppMetadata.kt`, `InstallState.kt`)
The installer state bus is an **application-scoped** `MutableSharedFlow(replay = 1)`. Its last emission lives for the whole process and is shared by *both* the APK installer and the Extensions store. Consequences:
- **Bitmap leak (`AP-A03`, #43/#57):** `InstallState.ReadyToInstall(meta)` carries a decoded `AppMetadata.icon: Bitmap`. Dismiss the installer by swiping the task away (so `onDismissRequest`→`resetState()` never runs) and the buffer pins the bitmap + `Intent` for the process lifetime.
- **Auto-parse suppressed (#22):** auto-parse only runs when `state is Idle`; a replayed `Success`/`ReadyToInstall` from a prior launch means sharing a new APK opens to a stale terminal state and **never parses**.
- **Cross-screen bleed + spurious refresh (#44/#50):** a `Success` left by the APK installer is replayed when the Extensions store opens → `LaunchedEffect(installState)` fires `viewModel.refresh()` → **unnecessary network catalog fetch**.
**Fix (root):** don't carry heavy Android objects or terminal state through an app-scoped replay flow. Either (a) `replay=0` + a per-ViewModel `StateFlow`, (b) emit a lightweight id/label-only state and load the icon via Coil, and (c) reset to `Idle` in `InstallerViewModel.onCleared()` / on installer (re)entry (`PortableInstallerActivity.onCreate`).

### L2 · Fire-and-forget `CoroutineScope` per progress tick during APK streaming — `{C}` `AP-K01`/`AP-K05` memory-leak
`data/repository/InstallerRepositoryImpl.kt:540` (findings #11/#13/#14, same root)
Inside the tracked-stream read loop, `updateProgress()` does `CoroutineScope(Dispatchers.IO).launch { eventBus.emit(...) }` on **every whole-percent change** — up to ~100+ detached, un-parented scopes per install, none stored or cancelled.
**Failure:** cancel a large (e.g. 300MB) install mid-copy → the install job cancels but the orphaned scopes keep emitting `Installing(...)` into the `replay=1` bus, so a late `Installing(0.87)` can land **after** a terminal state.
**Fix:** don't create a scope per tick — push progress onto a `Channel`/`MutableStateFlow` drained by the enclosing suspend fn, or emit directly from the copy loop on the current (already-IO) coroutine.

### L3 · `PackageInstaller` session leaked when `openSession` fails — `{C}` resource-leak
`data/repository/InstallerRepositoryImpl.kt:504`
After a successful `createSession()`, if `openSession(sessionId)` throws, **neither** error branch calls `abandonSession(sessionId)`.
**Failure:** on the Shizuku/Dhizuku privileged fallback `createSession` succeeds but `openSession` throws (UID/permission mismatch — the very reason it then falls back) → each failed attempt **leaks one active session**; repeated failures accumulate toward the per-app session cap.
**Fix:** `abandonSession(sessionId)` in a `catch`/`finally` covering everything between create and commit (ideally a helper that abandons by id).

---

## P1 — Anti-patterns (systemic, medium impact)

### A1 · One-off events modeled in `StateFlow` `UiState` instead of `SharedFlow` — `{C}`/`{P}` `AP-A02`
Violates `rules/general_guidelines.md` ("Never use StateFlow for one-time events"). Locations:
`presentation/permission/PermissionManagerScreen.kt:87` (errorMessage/successMessage, **{C}**) · `settings/SettingsViewModel.kt:51,61` (actionMessage, **{C}**) · `main/MainViewModel.kt:74` + `AppListViewModel` (actionMessage/freezerPrompt) · `appList/AppInfoDetailsViewModel.kt:30` · `freezer/FreezerViewModel.kt:42,43`.
**Failure (worst case, #24):** tap "Unfreeze all" then leave the Settings tab — in the Nav3 multi-backstack host the entry leaves composition, `collectAsStateWithLifecycle` unsubscribes, `consumeMessage()` never runs → the toast is lost (or replayed on return). Rotation between set-and-consume re-fires toasts.
**Fix:** expose `private val _events = MutableSharedFlow<UiEvent>()` (`MainViewModel` already has an `_effect` Channel — reuse it), collect in a `LaunchedEffect`/`ObserveAsEvents`, and remove the message fields from `UiState`.

### A2 · Hardcoded `Dispatchers.IO` with no central Koin dispatcher binding — `{C}`/`{P}`
Contract: dispatchers defined once and injected. Offenders:
use cases `AppBundleBuilder.kt:32`, `ExportAppUseCase`, `ShareAppUseCase` · VMs `ExtensionManagerViewModel:42`, `ExtensionBrowseViewModel:59`, `SettingsViewModel:77,188`, `InstallerViewModel:65`, `HomeViewModel:96,109`, `MainViewModel:283,301,315,460,521,560`.
**Impact:** these can't be driven by a `TestDispatcher`, so VM/use-case unit tests can't run deterministically off `Dispatchers.setMain`.
**Fix:** bind `single<CoroutineDispatcher>(named("io")) { Dispatchers.IO }` (+ Default/Main) in a central module and inject; prefer `flowOn` in the repository/use-case layer.

### A3 · Domain-purity violations — `android.*` in domain — `{C}` `AP-A03`
`domain/usecase/ExportAppUseCase.kt:28` (Context + MediaStore/DocumentFile/FileProvider) · `domain/usecase/ShareAppUseCase.kt:14` (Context/Uri/FileProvider) · `domain/usecase/AppBundleBuilder.kt:28` (Context) · `domain/model/AppMetadata.kt:3` (`Bitmap`) · `domain/model/AppInfo.kt` (`PackageManager`/`ApplicationInfo` + `mapToAppInfo()`) · `InstallState` (`Intent`), `ApkDetails` (`Drawable`).
**Impact:** business logic coupled to the framework, untestable without Robolectric/emulator, not KMP-portable; the `Bitmap`/`Drawable` on models are also what makes L1 leak.
**Fix:** move Context/SAF/MediaStore work behind data-layer ports (e.g. `FileExporter`, `FileShareRepository`) returning plain paths/URIs-as-String; replace `Bitmap`/`Drawable` with an icon key/path resolved by Coil in the UI; map `Intent` to a domain-neutral confirmation token in presentation.

### A4 · Framework types injected into ViewModels — `{C}` `AP-A04`
`presentation/appList/AppListViewModel.kt:76` (`Context` for `getString` — **{C}**, findings #20/#21/#49 merged) · `main/MainViewModel.kt:88` + `installer/InstallerViewModel.kt:29` (`PackageManager`, and `Uri` in the installer VM).
**Impact:** VMs untestable without a real `Context`/`PackageManager`; localization at VM-time yields stale labels on runtime locale change; bypasses the `AppRepository`/`SystemRepository` abstraction the rest of the app uses.
**Fix:** return `UiText`/identifiers from the VM and resolve strings in Compose (`stringResource`); route package lookups through `AppRepository`/`SystemRepository`.

### A5 · State split across multiple flows per screen — `{P}` `AP-A02`
`installer/InstallerViewModel.kt:35` (installState + installMode + availableModes + a mutable public `var currentPackageName`) · `settings/SettingsViewModel.kt:97` (`uiState` + duplicate `preferences` + `extensionConsentAccepted`, three `stateIn` collectors of the same `userPreferences`).
**Impact:** subscribers can observe mutually-exclusive states transiently (e.g. `installMode` not yet in `availableModes`); duplicated surfaces desync easily.
**Fix:** one immutable `UiState` per screen exposed as one `StateFlow`; keep genuine one-offs (e.g. `UserConfirmationRequired` intent) on a separate `SharedFlow`.

---

## P1 — Correctness bugs & robustness (medium)

- **`{C}` #16 — Reflection uninstall fired twice** · `shizuku/ShizukuReflector.kt:147` — for reset-to-factory system apps the `PackageInstaller.uninstall` reflection runs inside `if(shouldReset)` **and** again unconditionally with identical args. → remove the redundant call / pass distinct flags.
- **`{C}` #17 — Async uninstall/reinstall reports success without awaiting result** · `ShizukuReflector.kt:158` — builds a result `PendingIntent`, invokes the async `PackageInstaller.uninstall`/`installExistingPackage`, then `return true`; the `IntentSender` outcome is never observed → UI reports "uninstalled" while the app remains. → await via `CompletableDeferred`/one-shot receiver, or re-query PM and return observed state.
- **`{C}` #25 — `FreezerTileService` ignores `FreezerMode`** · `tile/FreezerTileService.kt:71` — hard-codes `setAppDisabled(pkg, true)`; never reads `PreferenceRepository`, so QS-tile freezing **always disables even when the user chose Suspend**, diverging from in-app/launcher restore logic. → inject `PreferenceRepository` and branch `setAppSuspended` vs `setAppDisabled`.
- **`{C}` #6 — `MainShell` async factory swallows `NoShellException`** · `suCore/internal/MainShell.kt:53` — on failure it only calls `Utils.ex(e)`; the `GetShellCallback` is never invoked → awaiting coroutines (e.g. `isRootGranted`) **suspend forever**. → resume with `resumeWithException`/failure callback.
- **`{C}` #7 — `MainShell.get()` bricks shell creation after one failure** · `suCore/internal/MainShell.kt:30` — sets `isInitMain=true`, and the reset to `false` is only reached on success (no `try/finally`) → a single transient build failure makes every later `Shell.getShell()` throw "main shell died" for the rest of the process. → reset in `finally`.
- **`{C}` #26 — Tile bulk-freeze tied to tile-service lifetime** · `tile/FreezerTileService.kt:46` — long freeze-all runs on `longRunningScope`, cancelled in `onDestroy()`; the QS shade collapse destroys the service mid-loop → **partial freeze**, no result toast. → delegate to WorkManager / an app-scoped worker.
- **`{C}` #27 — `Application.onCreate` fires an uncancelled `MainScope()` with no error handling** · `ThorApplication.kt:77` — DataStore `.first()` / shortcut sync / locale apply with no `runCatching`; a DataStore `IOException` or `ShortcutManager` rate-limit crashes at startup. → `runCatching` + a retained, injected app scope.
- **`{P}` #15 — `AutoFreezeManager` risks the BroadcastReceiver timeout** · `data/service/AutoFreezeManager.kt:51` — on `SCREEN_OFF` it `goAsync()` then freezes the entire list (privileged shell, `join()` all) before `finish()`; a large list exceeds the ~10s dispatch budget → ANR/timeout + partial freeze; rapid on/off stacks batches. → drop `goAsync()`, hand to the app scope, add an in-flight guard.
- **`{C}` #9 / #10 — Exported providers block a binder-pool thread with `runBlocking`** · `provider/ExtensionOpsProvider.kt:65` (N sequential privileged shell ops), `provider/FreezerBridgeProvider.kt:51` (root unfreeze on launcher hook) — `AP-K02`. A hung/slow root shell pins the binder thread and can starve the pool / stall the launcher. → `runBlocking { withTimeout(...) { } }`, and prefer queueing onto the app scope.
- **`{P}` #61 — `DisposableEffect` keyed on a non-observable `var`** · `installer/PortableInstaller.kt:140` — keys off `viewModel.currentPackageName` (plain property); Compose can't observe it, so receiver re-registration only happens as a side effect of other recomposition. → expose it through `UiState`/`StateFlow`.
- **`{P}` #40 — Package name interpolated into shell command without escaping** · `shizuku/Shizuku.kt:384` — the Shizuku uninstall/reinstall path skips `ShellUtils.escapedString()` that the Dhizuku helper uses. Low real risk (package names are constrained) but a consistency/robustness gap. → escape all interpolated identifiers.
- **`{P}` #48 — Flow collectors launched without error handling** · `AppListViewModel.kt:135`, `HomeViewModel.loadDashboardData:98`, `MainViewModel` `.first()` L197/337 — `getInstalledAppsUseCase()` is a `callbackFlow` that can throw; unguarded collection can crash (and leaves loaders stuck) — `FreezerViewModel.observeApps` already does this correctly. → wrap in `runCatching`/`Flow.catch` → error state.

---

## P2 — Performance

- **`{C}` #4 `AP-C14` — Component lists render eagerly in a non-lazy `Column`** · `appList/AppInfoDetailsScreen.kt:1163` — each of the 4 component sections is a single LazyColumn item whose body is `Column { items.forEach { Row } }`; expanding a large system app composes hundreds–thousands of rows synchronously → jank/OOM risk. → `LazyColumn items(list, key=…, contentType=…)`; move per-keystroke filtering off the main thread.
- **`{C}` #5 — `Bypass` mmaps + parses the boot-classpath dex on the main thread every cold start** · `bypass/Bypass.kt:73` — `getCachedOffsetData()` is null (disk cache never activated) so `DexFieldLayout().scanPath(...)` runs in `Application.onCreate` each launch → startup latency / ANR risk on slow storage. → `Bypass.init(context)` before first use; read the persisted cache first and/or run off the main thread; make `setCachedOffsetData` actually persist.
- **`{P}` #8 — `ExtensionManager.loadExtensions()` rebuilds every `PathClassLoader` + N+1 PM scan per call** · `data/manager/ExtensionManager.kt:55` — nothing cached; every Extensions-screen resume re-loads identical extension classes and fires `1 + N` full PM scans; repeated dynamic class loading grows class-metadata memory. → cache by `packageName`→instance keyed on installed version; pass out `ApplicationInfo` to avoid the per-extension lookup.
- **`{C}` #53 — `FreezerViewModel.pinBulkShortcut` builds a 216×216 bitmap + binder IPC on the main thread** · `freezer/FreezerViewModel.kt:401` — the sibling `pinAppToLauncher`/`pinAllToLauncher` correctly use `viewModelScope.launch(Dispatchers.Default)`; this one runs inline → click-time frame hitch. → offload to `Dispatchers.Default`.
- **`{P}` #51 — Synchronous `getPackageInfo` IPC in composition** · `extension/ExtensionManagerScreen.kt:105` — `isLegacyInstalled` runs a blocking binder call inside `remember{}` on the main thread → first-frame stall. → move to VM (`produceState`/`withContext(IO)`).
- **Redundant privilege re-probing (perf theme):**
  - **`{P}` #36** `data/repository/SystemRepositoryImpl.kt:37` — `getActiveGateway()` re-probes **root + Shizuku + Dhizuku** on *every* privileged action → `2N+` IPC calls for an N-app batch, plus a second source of truth vs `PrivilegeManager.state`. → inject `PrivilegeManager` and select from cached `state.first()`.
  - **`{P}` #35** `ShizukuSystemGateway.kt:191` — extra `checkSelfPermission()` + `pingBinder()` per action. → drop the per-action gate / cache briefly.
  - **`{P}` #47** `appList/AppInfoDetailsViewModel.kt:114` — every action calls `loadAppDetails()` which re-runs 3 privilege probes + a full detail scan → visible loader flash per tap. → probe once; add a "refresh detail only" path.
- **`{C}` #31 — Redundant `derivedStateOf` keyed on the state it reads** · `widgets/AppList.kt:128` — `remember(multiSelection){ derivedStateOf { … } }` reallocates + recomputes every change, so `derivedStateOf` never helps. → `remember(multiSelection){ multiSelection.mapTo(HashSet()){it.packageName} }`.

---

## P2 — Thread-safety

- **`{P}` #33 — Non-thread-safe lazy singletons** · `suCore/internal/RootServiceManager.kt:66` (+ `RootServiceServer.getInstance`) — unsynchronized check-then-create; two concurrent calls could build two managers (double receiver / FileObserver registration), corrupting IPC bookkeeping. → `@Synchronized`/`by lazy`.
- **`{P}` #55 — `HomeViewModel` shares mutable app-list `var`s across coroutines** · `home/HomeViewModel.kt:55` — `lastUserApps`/`lastSystemApps` written from one IO coroutine, read from another with no happens-before → an early USER/SYSTEM toggle can render all-zero stats. → derive reactively in the `combine`, or `@Volatile` + single-thread confinement.
- **`{P}` #34 / #41 — `cachedUserId` unsynchronized & never invalidated** · `RootSystemGateway.kt:558`, `shizuku/Shizuku.kt:368` (+ DhizukuHelper) — benign race, but the process-lifetime cache is a **correctness gap**: after a multi-user/work-profile switch, `--user <old id>` keeps targeting the wrong user. → guard (`@Volatile`/mutex) **and** invalidate on user-switch.

---

## P2 — Compose / UI polish

- **`{C}` #29 `AP-C09` — `remember` for user input lost on config change** · `appList/AppInfoDetailsScreen.kt:932` (permissions search), `:1044` (components search), `:249` (selected tab) — typed filter & selected tab reset on rotation. → `rememberSaveable`.
- **`{C}` #60 — `collectAsState` instead of `collectAsStateWithLifecycle`** · `installer/PortableInstaller.kt:91` — the two sibling flows already use the lifecycle-aware variant; this one keeps collecting while backgrounded. → switch to `collectAsStateWithLifecycle`.
- **`{C}` #66 — Derived flags in `mutableStateOf` re-synced via `LaunchedEffect`** · `widgets/MultiSelectToolBox.kt:54` — `hasFrozen`/`hasUnFrozen`/… are pure derivations of `selected` but stored + updated in an effect → one-frame stale-buttons flicker + extra recomposition. → compute directly (`val hasFrozen = selected.any { !it.enabled }`).
- **`{C}` #54 — Double-counted bottom nav inset** · `home/HomeScreen.kt:91` — Scaffold `innerPadding.bottom` already covers the nav bar, and `HomeScreen` adds `80.dp + navigationBars` on top → large dead gap above the nav bar (verify on-device). → pick one owner of the bottom inset.
- **`{C}` #30 — Hardcoded English strings in the system-app uninstall dialog** · `widgets/AppInfoDialog.kt:222,227,246` — mixed-language dialogs for non-English users; `AppInfoDetailsScreen` already has the `R.string` equivalents. → reuse the existing resources.

---

## P2 — Dead code & minor

- **`{P}` #38 — `data/source/local/ShellDataSource.kt:14`** — never referenced / not in any Koin module, yet its `init` calls `Shell.setDefaultBuilder(...)` (global libsu state). If ever wired up it would clobber `ThorShellConfig`. → delete, or make it the single owner of libsu config.
- **`{P}` #39 — `shizuku/PackageManagerExt.kt:23`** — `getAllPackagesInfo()` / no-arg `getInstalledPackages()` are dead code that double-enumerate PM + per-app `loadLabel()`. → delete or fold into `AppRepositoryImpl`.
- **`{P}` #32 — `suCore/internal/CoroutineStreamGobbler.kt:15`** — "under development", unused (live path uses `StreamGobbler`), carries an unresolved non-thread-safe caveat. → remove or finish + integrate.
- **`{P}` #37 `AP-R02` — `room/ExtensionDataDao.kt:11`** — blocking (non-`suspend`/non-`Flow`) DAO fns unlike `AppDao`/`FreezerDao`; safe only because the one caller wraps them in `withContext(IO)`. → mark `suspend` (drop-in).
- **`{C}` #42 — `printStackTrace()` instead of `Logger`** · `shizuku/ShizukuReflector.kt:211` — privileged-op failures lose tag/level in the field. → `Logger.e(TAG, msg, t)`.
- **`{C}` #46 — Dispatchers hardcoded in use cases** (see **A2**) · `AppBundleBuilder.kt:32`.

---

## Rejected findings (12) — verified false positives / already mitigated

Recorded for transparency; the adversarial verifier refuted each against current source:

1. `MainViewModel.kt:88` — "PackageManager injected into VM" (dup of the confirmed **A4**; this instance rejected as double-count).
2. `FreezerViewModel.kt:336` — hardcoded dispatchers (already offloaded correctly here).
3. `MainScreen.kt:317` — "NavDisplay entryProvider rebuilt every recomposition" — it *is* remembered.
4. `PortableInstaller.kt:112` — "LocalContext→Activity cast auto-parses unrelated `ACTION_VIEW`" — guarded.
5. `FreezerViewModel.kt:42` — one-off toast via StateFlow — consumption idiom mitigates re-fire here.
6. `AppRepositoryImpl.kt:192` — `awaitClose` crash if 2nd `registerReceiver` fails — not reachable as described.
7. `InstallReceiver.kt:32` — ad-hoc `CoroutineScope` per `onReceive` — acceptable (`goAsync` bounded).
8. `RootServiceManager.kt:115` — "receiver never unregistered" — unregistered on teardown.
9. `ShellExtensions.kt:51` — "empty `awaitClose` leaks shell job" — job completes/cancels via other path.
10. `SettingsViewModel.kt:90` — "multiple StateFlows per screen" — refuted (real split is #64 elsewhere).
11. `PortableInstaller.kt:91` — lifecycle collection (kept as the confirmed #60 instead).
12. `HomeViewModel.kt:96` — "collect blocks lack error handling" — kept as the confirmed #48 instead.

---

## Recommended remediation order

1. **P0 (H1–H3):** the rotation crash, the root-bind deadlock, and the Extensions-screen crash are user-visible and cheap to fix.
2. **Leaks L1–L3:** redesign `InstallerEventBus` (kills 5 findings incl. the Bitmap leak) + fix the install-stream scope/session handling.
3. **Robustness bugs:** `MainShell` (#6/#7), tile mode+lifecycle (#25/#26), provider `runBlocking` timeouts (#9/#10), `Application.onCreate` guard (#27).
4. **Systemic anti-patterns A1–A5:** introduce the central Koin **dispatcher module** and a **`SharedFlow` event channel** convention, then migrate screens; extract domain ports for the framework-bound use cases.
5. **P2 perf / thread-safety / polish / dead-code** as fast-follows.

> **Note on the anti-pattern cache:** two patterns recurred here that the brain ledger doesn't yet name precisely — (a) *app-scoped `replay=1` `SharedFlow` holding heavy Android objects / replaying terminal state across screens*, and (b) *creating a fresh `CoroutineScope` inside a hot loop / per progress tick*. Consider contributing these to `android-agent-brain` (`add_anti_pattern`) so future generations flag them. I did **not** write to the shared vault — say the word and I'll add them.

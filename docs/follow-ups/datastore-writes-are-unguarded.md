# The DataStore **write** path has no guard

**Filed:** 2026-08-04 (UTC) · **Tier 3** — filed, decision still open · **Effort:** small-to-medium
**Raised by:** PR #339, which guarded the *read* path and deliberately stopped there.

## What #339 fixed, and what it did not

PR #339 made an unreadable settings file survivable. Both stores got a
`ReplaceFileCorruptionHandler`, and `guardedRead` retries a transient `IOException` before falling
back to `emptyPreferences()` with a `degraded` flag that surfaces to the user. A settings file Thor
cannot *read* no longer crash-loops the app.

Writes got none of that. `PreferenceRepositoryImpl` has **25 `edit { }` blocks**, every one of them
bare:

```kotlin
override suspend fun setThemeMode(themeMode: ThemeMode) {
    context.dataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
}
```

`DataStore.edit` writes to a temp file and renames it. On a full disk, a read-only filesystem, a
device in the middle of a storage-migration, or an SELinux denial it throws `IOException` — the same
class of failure #339 spent a whole PR making survivable in the other direction.

## Why that is a crash and not a dropped setting

Every caller is the same shape:

```kotlin
fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { preferenceRepository.setThemeMode(mode) }
}
```

`viewModelScope` is a `SupervisorJob` on `Dispatchers.Main.immediate` with **no**
`CoroutineExceptionHandler`. A supervisor does not swallow its children's exceptions; it only stops
them cancelling their siblings. So the `IOException` reaches the thread's default uncaught handler
and Thor dies — on the main thread, from the user tapping a theme switch.

Confirmed by inspection on 2026-08-04: **zero** callers of any `preferenceRepository.set*` /
`update*` / `toggle*` wrap the call in `try`. `SettingsViewModel` alone has ten such launches;
`AppListViewModel.toggleAppListIsGrid` and `SecurityViewModel`'s self-heal are two more.

## The two failures that are worse than a crash

1. **`SecurityViewModel`'s self-heal never retries.** The disarm writes `setBiometricLock(false)`
   from inside a `collect { }`. A throw there cancels the collector for the life of the process, so
   a device that could not open its own app lock stays locked out *and* stops trying to fix itself —
   the exact dead end that self-heal exists to prevent.

2. **`setLanguage` is two writes that must both land.** `SettingsViewModel.setLanguage` calls
   `preferenceRepository.setLanguage(language)` and then `localeManager.applyLocale(language)`. If
   the first throws, the second never runs, and the user gets neither the persisted preference nor
   the applied locale — but if the *ordering* were ever swapped, they would get a locale applied to
   a preference that says otherwise, which survives the restart.

## What a fix would have to decide

This is filed rather than fixed because "retry it" is not obviously right for a write, and the
decision is a product one:

- **Which writes are worth retrying?** A theme toggle can be dropped. `setBiometricLock(false)` on
  the self-heal path cannot — it is the difference between a recoverable and an unrecoverable app.
- **What does the user see?** #339 established the pattern: a `degraded` flag carried to
  `SecurityViewModel`, reported once per process through the existing `Channel<UiText>`. A failed
  *write* has a natural equivalent ("Thor could not save that setting"), but a toast per failed
  toggle on a genuinely full disk is its own kind of broken.
- **Where does the guard live?** A `guardedWrite` helper beside `guardedRead` keeps the pair
  together and is one edit per setter (25 of them). A `CoroutineExceptionHandler` on
  `viewModelScope` is one edit total but catches every unrelated bug too, and turns crashes that
  *should* be loud into silence.
- **Does the UI state need to roll back?** Every one of these settings is read back from the same
  flow, so a failed write self-corrects on the next emission — except where a caller has already
  acted on the value it *thought* it wrote, which is the `setLanguage` case above.

## Not urgent, and why

The read path was the one that crash-*looped* — it failed on every launch, before any UI, with no
way for the user out short of clearing app data. A write failure is one tap, one crash, and the app
comes back. It is a real defect, but it is not the one that made #339 urgent, and shipping a
half-considered retry policy over the money-path setter (`setBiometricLock`) would be worse than
leaving it visible here.

## Related

- PR #339 — the read-path guard, and the `guardedRead` shape any fix should mirror.
- [`viewmodel-behavior-tests.md`](viewmodel-behavior-tests.md) — the mutation-checking bar a test
  for this would have to clear; a test that asserts "the setter was called" would not catch it.

## Thor v1.93.3

28 commits since v1.93.2. The theme is honesty about results: several privileged
actions used to report success from an exit code that never meant success.

### Fixed — privileged actions

- **Judge privileged actions by a readback, not an exit code** (`3cbb43e4`). `pm`
  and `am` return 0 for work they did not do; Thor now re-reads the state it
  asked for.
- **Clear-data waits for the observer** instead of reporting a wipe that was only
  dispatched (`82152a24`).
- **The app-ops fallback stops reporting a restriction it never applied**
  (`e95aa903`).
- **Every privileged command names the user**, per that command's own AOSP
  default (`ffc45da7`) — work profiles and secondary users were being targeted by
  accident.
- **One mute ROM no longer costs a batch fifteen seconds per app** (`25506ba1`).
- **Dhizuku tries the per-user cache overload first**, as Shizuku already did
  (`d1272b77`).
- **Root schedules the stale unbind** the H2 timeout used to throw away
  (`7f977814`).

### Fixed — security and stability

- **App lock covers cold start**, and the app list no longer leaks to Recents
  (`5c487ee2`).
- **An unreadable settings file no longer crash-loops the app** (`3a316da8`).
- **The installer makes the confirmed identity and the installed bytes one set**
  (`7eee6dbd`), and **cancels a superseded parse** so it reclaims its own staged
  copy (`318316a8`).

### Fixed — localisation

- **The in-app language picker works below API 33** (`eddbd9b4`).
- **A language change reaches the caches that hold copies** (`5746afde`).

### Billing

- **Support tiers are probed, not hardcoded** (`d61a89c8`). Play exposes no
  catalogue-enumeration API, so Thor queries a candidate ID set and renders
  whatever Play answers with — a tier added in Play Console appears without an
  app release. Prices and their order come from Play's own localized figures.
- **The details a purchase is charged from stay on the same snapshot as the list
  shown** (`8f6ce8dc`).
- **A resume rebuilds a connection the retry ladder gave up on** (`d9db1ac0`),
  and **`isReady` is no longer consulted** where the reconnect flag made it a
  constant true (`472dc88a`).

### Internal

- R8 now runs on every PR, so the first minified build is not the one that ships
  (`0ac629d0`).
- Named dispatchers are injected rather than hardcoded (`ccbc08a3`).
- Follow-up index reswept and dated in UTC (`9f772b91`, `1e5ab647`).

**Full changelog**: https://github.com/trinadhthatakula/Thor/compare/v1.93.2-dev-106...v1.93.3

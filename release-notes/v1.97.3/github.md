# Thor v1.97.3 Release Notes

Version **1.97.3 (1973)** is a bug-fix update covering changes after the published
**v1.97.2-dev-148** release through merged dev commit `31ada831`.

Source comparison:
[`v1.97.2-dev-148...31ada831`](https://github.com/trinadhthatakula/Thor/compare/v1.97.2-dev-148...31ada831e860dfacb55c8e1527d9652f9c7256e1).

## ✨ Highlights

- ❄️ **A working Quick Settings toggle:** freeze eligible watchlist apps, then tap again to
  unfreeze. Live watchlist changes, clearer counts and terminal results keep the tile current.
- ⏸️ **Preserve other apps’ pauses:** Freeze and Unfreeze respect Digital Wellbeing and other
  suspension owners. Thor reports a remaining pause rather than trying to launch through it.
- 🔄 **Keep recovery available:** successfully applied freezes and suspensions stay tracked
  even when optional mode-conversion cleanup fails. Profile recovery distinguishes a verified
  external pause from a Thor restriction that still needs restoring.
- 💬 **Correct Suspend confirmation:** a successful Suspend action retains its own success
  message, including when another app also holds a pause.

## What's Changed

### ❄️ Quick Settings Freeze and Unfreeze

[PR #597](https://github.com/trinadhthatakula/Thor/pull/597)
(`d59d69e2`, `51b335e1`, `cd382f6d`):

- Resolve both directions from live watchlist and package state. Mixed watchlists unfreeze
  their frozen members first, preserving recovery after a partly successful freeze.
- Show the relevant next action, frozen/eligible counts, ongoing progress and completion
  results. Refresh candidates when Room membership or package/suspension broadcasts change.
- Keep enqueue handoff in the process-owned scope so closing the shade does not cancel
  accepted work. Repeated taps reconnect to queued or running work; canceled refreshes cannot
  publish stale snapshots after the listening lifecycle ends.
- Retain freeze safety filters while allowing unfreeze recovery, and provide the new tile
  text in all nine supported app locales.

Mahfuj ([sigmahfuj](https://github.com/sigmahfuj)) contributed the original unfreeze-count
change. His [original commit](https://github.com/sigmahfuj/Thor/commit/cfcc1d5e0819963256b5f7ba5fd5dde4f8c6e008)
is preserved with its author and date as `d59d69e2`.

### ⏸️ Coexistence with Digital Wellbeing and other suspension owners

[PR #599](https://github.com/trinadhthatakula/Thor/pull/599)
(`cc645878`, `24eb4bc7`):

- **Freeze:** disable/hide first, then remove only verified Thor suspension entries when
  changing modes. Keep independently owned pauses intact. A verified disable stays successful
  and tracked if ownership cleanup cannot complete.
- **Suspend:** add a separate verified Thor entry without first unsuspending the app. Attempt
  enable/unhide only after that entry is verified. Ordinary cleanup failures keep the applied
  suspension successful and tracked; cancellation still propagates.
- **Unfreeze:** enable/unhide first, then remove only verified Thor entries. Preserve unknown
  ownership and report remaining pauses in app feedback, durable task details and the tile.
  Launcher shortcuts avoid launching through a known remaining pause.
- Root uses typed, user-scoped ownership readback. Shell Shizuku marks its shared entry with a
  persistent per-package/user nonce in launcher extras; overwritten or unmarked shared entries
  are preserved rather than claimed by Thor.
- Android 9 Suspend is skipped. Android 10 cannot overlay an existing external suspension.
  Dhizuku Suspend is skipped because its shared device-owner API cannot verify an independent
  Thor entry; Unfreeze preserves and reports a confirmed suspension with unidentified ownership.
  Freeze remains available without silently changing the selected mode.
- Watchlist toggling distinguishes verified external-only pauses from Thor restrictions.
  Profile removal rechecks live ownership so verified external pauses do not trap a successfully
  restored app in its recovery dialog. Unknown or newly added Thor restrictions remain protected.

### 💬 Suspend success feedback

[PR #600](https://github.com/trinadhthatakula/Thor/pull/600) (`d4c7f7f2`):

- Restrict restoration feedback to **UnFreeze** and **UnSuspend**. **Suspend** continues to
  show its successful suspension confirmation even when Digital Wellbeing or another owner
  also keeps the app paused.
- Cover all three actions with known, unidentified and no remaining owners in the regression test.

## 🔧 Project: dependencies, security and release artifacts

- **Android dependencies — [PR #583](https://github.com/trinadhthatakula/Thor/pull/583),
  [PR #590](https://github.com/trinadhthatakula/Thor/pull/590) and
  [PR #598](https://github.com/trinadhthatakula/Thor/pull/598)**
  (`83769211`, `7d271b9c`, `f676fbb0`): update AGP to **9.6.0-alpha02**, Gradle to **9.8.2**,
  DataStore to **1.3.0**, Material 3 to **1.5.0-beta02**, serialization to **1.12.0** and
  Dhizuku to **2.7.0**.
- **Website security and dependencies — [PR #587](https://github.com/trinadhthatakula/Thor/pull/587)
  and [PR #596](https://github.com/trinadhthatakula/Thor/pull/596)** (`fb319d89`, `de119981`):
  update Sharp to **0.35.5**, patching its bundled librsvg dependency
  (**CVE-2026-96889 / GHSA-wq5f-xc86-pv6w**). Refresh the Astro stack and transitive
  fast-uri **3.1.9** and Zod **4.6.7** dependency resolutions.
- **Release and CI dependencies — [PR #586](https://github.com/trinadhthatakula/Thor/pull/586)
  and [PR #588](https://github.com/trinadhthatakula/Thor/pull/588)** (`03b52a61`, `73a20eae`):
  update Fastlane to **2.241.2**, Java setup and CodeQL actions.
- **Release preparation:** bump only `versionCode` to **1973**, deriving **1.97.3**; add the
  three curated notes and complete Fastlane copies for **en-US**, **en-GB** and **hi-IN**.
  Retire the **v1.93.0** curated directory to retain 20 releases. Historical Fastlane changelogs
  remain. Shizu continues to follow its separate production-pinned synchronization procedure.

## 🧪 Validation scope

The **1973 release branch** passed `./gradlew test lintFossDebug lintStoreRelease` with
**3,742 JVM tests per debug flavor**, zero failures, errors or skips, and no lint errors,
MissingTranslation warnings or SyntheticAccessor findings. All **13 release-script suites**
passed before and after retention pruning. Play notes use **466/500 characters**; the assembled
Telegram caption uses **870/1024 UTF-16 units** with the conservative wrapper. All three
Fastlane copies match the complete Play notes.

The PR-mode Shizu checker reports an existing changelog mismatch against production **1.97.1**.
Its separate production-pinned synchronization follows the procedure in
[the release-notes guide](https://github.com/trinadhthatakula/Thor/blob/31ada831e860dfacb55c8e1527d9652f9c7256e1/release-notes/README.md#step-5--propagate-playstoretxt-to-fastlane-locales).

The rooted-device Quick Settings cycle and Digital Wellbeing coexistence checks remain pending.
JVM/Robolectric results do not establish Android/OEM behavior or verify the reported Instagram
issue. Compatibility and device checks are recorded in
[the suspension follow-up](https://github.com/trinadhthatakula/Thor/blob/31ada831e860dfacb55c8e1527d9652f9c7256e1/docs/follow-ups/external-suspension-coexistence.md).

## 🛠 Commits Log

Non-merge commits after `v1.97.2-dev-148`, in chronological order:

- `83769211` chore(deps): bump the maven group with 3 updates
- `7d271b9c` chore(deps): bump the maven group across 1 directory with 7 updates
- `03b52a61` chore(deps): bump fastlane
- `fb319d89` chore(deps): bump the web group across 1 directory with 5 updates
- `73a20eae` chore(deps): bump the actions group across 1 directory with 2 updates
- `de119981` fix(web): patch sharp librsvg vulnerability
- `d59d69e2` Add unfreezeCount tracking to FreezerTileService
- `51b335e1` fix(freezer): harden Quick Settings toggle and feedback
- `cd382f6d` fix(freezer): refresh tile for live watchlist and package changes
- `f676fbb0` chore(deps): bump the maven group with 4 updates
- `cc645878` fix: preserve external suspensions during freeze and restore
- `24eb4bc7` fix: retain preserved suspensions and successful suspend tracking
- `d4c7f7f2` fix: keep suspend success feedback separate from restoration

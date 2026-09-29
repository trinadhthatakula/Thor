# App preference editor

Status: deferred by the maintainer on 2026-09-30; no implementation in the Sett Edit PR.

## Scope

Edit a selected app's own preference files. Entry points belong in the app details sheet,
app details activity and equivalent app information surfaces. This is separate from #504:
Sett Edit edits Android's shared System, Secure and Global settings and opens from Extensions.

## Decisions before implementation

- Verify access to the selected app's private data for each supported provider. Root is the
  candidate; ordinary Shizuku shell access does not grant arbitrary app-private file access.
  Do not promise Shizuku support without device evidence. Dhizuku is out of scope.
- Choose the supported formats and storage locations. Start by assessing SharedPreferences XML;
  DataStore, databases, encrypted preferences and custom formats need separate handling.
- Define a separate first-use warning, change preview and recovery flow before allowing writes.
- Decide how to handle a running app that caches or overwrites its preferences, locked credential
  storage and user/profile scope. A stored file change does not guarantee an app behavior change.
- Preserve a before-image and file ownership, permissions and SELinux labels. Validate safe
  replacement and restoration on disposable apps before testing any personal app data.

## Acceptance for a future PR

Use a topic branch from dev. Test on emulators first, then an authorized physical device using
disposable app data. Verify provider refusal, malformed/unsupported files, concurrent app writes,
failed persistence, exact readback and restoration. Run the repository's required test/lint gates.

# AGENTS.md — Agent & Contributor Guidelines for Thor

Guidelines and mandatory operating rules for all AI agents and contributors working in this repository.

## 🚨 Mandatory Branching & Pull Request Workflow

1. **Permanent Branches**:
   - `dev`: Primary active integration branch.
   - `master`: Open testing / beta track.
   - `production`: Public release track.

2. **No Direct Commits**:
   - **Never commit directly to `dev`, `master`, or `production`.**
   - Every change (code, tests, documentation, dependencies, localizations) MUST be made in a dedicated topic branch branched from `dev`.

3. **Branch Naming Conventions**:
   - `feat/<feature-name>` or `feature/<feature-name>`
   - `fix/<bug-or-issue-name>`
   - `i18n/<locale-code>` or `translate/<locale-code>`
   - `docs/<doc-name>`
   - `chore/<task-name>`

4. **Pull Requests**:
   - Always open Pull Requests targeting the **`dev`** branch.
   - Never open a PR against `master` or `production`.
   - Do **NOT** bump `versionCode` in standard feature/fix PRs.

## 📦 Release Cadence & Release Notes Generation

Follow [`release-notes/README.md`](release-notes/README.md) and [`docs/branching-and-releases.md`](docs/branching-and-releases.md):

1. **Release Cadence Structure**:
   - **Dev / Pre-Releases** (`v1.94.1`, `v1.94.2`, etc.): Incremental feature & bug-fix iterations on `dev` (uploaded to Play `alpha`/`internal`).
   - **Major / Stable Releases** (`v1.93.0`, `v1.94.0`, `v1.95.0`, `v2.0.0`): Consolidated, cumulative stable releases uniting everything shipped across the intermediate dev cycles (refer to `1.93.0` to `1.94.0` changelogs).

2. **Required Release Notes Artifacts** (`release-notes/v<versionName>/`):
   - `playstore.txt`: Maximum 500 characters. Single source propagated to Play Store, F-Droid (`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`), and Shizu Store (`shizu_store.json`).
   - `telegram.md`: Telegram broadcast caption (assembled length strictly <= 1024 UTF-16 units).
   - `github.md`: Full GitHub release body (Major releases feature comprehensive Highlights, categorized changes by feature with PR links, bug fixes, and technical reflections).

3. **Pre-flight Budget Gate**:
   Always run before opening a release PR:
   ```bash
   .github/scripts/check-notes-budget.sh <versionName>
   ```

## 🛠️ Verification & Build Gates

Before opening or requesting review on a PR, ensure all gates pass locally:
```bash
./gradlew test lintFossDebug lintStoreRelease
```
- No `MissingTranslation` warnings in any locale.
- No `SyntheticAccessor` errors in `:bypass` or unhandled lint errors.
- Ensure all tests pass.

---
trigger: always_on
description: Mandatory Git branching and PR workflow rules for Thor
---

# Mandatory Git Branching & Pull Request Rule

All AI agents and contributors working in the Thor codebase MUST follow these branch and PR rules without exception:

## 1. Permanent Branches (Protected)
- `dev`: Primary integration branch. All development lands here first.
- `master`: Open testing / beta branch. (Updated only via PR from `dev`).
- `production`: Public release branch. (Updated only via PR from `master`).

**NEVER commit directly to `dev`, `master`, or `production`.**

## 2. Topic Branches for Every Change
Every change (features, bug fixes, refactors, docs, i18n) MUST be implemented on a dedicated topic branch cut from the latest `dev`:
- `feat/<topic-name>` or `feature/<topic-name>` — for new features & enhancements
- `fix/<issue-name>` — for bug fixes and patches
- `i18n/<locale>` or `translate/<locale>` — for localization additions/fixes
- `docs/<topic-name>` — for documentation updates
- `chore/<topic-name>` — for dependencies, maintenance, and toolchain updates

## 3. Pull Request Requirements
- **Target Branch**: Always open Pull Requests **against `dev`**.
- Never open PRs directly against `master` or `production`.
- Do **NOT** bump `versionCode` or `versionName` in standard feature/fix PRs — releases are cut through dedicated release workflows.
- Ensure all CI gates pass (`./gradlew test lintFossDebug lintStoreRelease`) before requesting merge.

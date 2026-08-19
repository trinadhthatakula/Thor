---
trigger: always_on
description: Release cadence and release notes generation rules for Thor
---

# Release Cadence & Release Notes Rules

All agents preparing releases or release notes in Thor MUST follow these standards:

## 1. Release Cadence
- **Incremental Dev Releases** (`v1.94.1`, `v1.94.2`, etc.): Focused on intermediate features, patches, and dependencies.
- **Major / Stable Releases** (`v1.93.0`, `v1.94.0`, `v1.95.0`, `v2.0.0`): Stable cumulative releases that consolidate all changes shipped across the preceding dev releases into a unified, high-level changelog (see `v1.93.0` → `v1.94.0` for reference structure).

## 2. Directory Structure (`release-notes/v<versionName>/`)
Every release directory MUST contain exactly three files:
1. `playstore.txt`: At most 500 characters. Propagates identically to:
   - Google Play "What's new"
   - F-Droid metadata (`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`)
   - Shizu Store (`shizu_store.json`)
2. `telegram.md`: Telegram channel broadcast caption (must fit within 1024 UTF-16 units including the workflow wrapper).
3. `github.md`: Full Markdown release notes for GitHub Releases:
   - Major releases: Highlights, grouped features, PR links, bug fixes, security/reliability notes.
   - Dev releases: Focused summary of the specific batch.

## 3. Pre-flight Validation
Always validate character budgets with:
```bash
.github/scripts/check-notes-budget.sh <versionName>
```

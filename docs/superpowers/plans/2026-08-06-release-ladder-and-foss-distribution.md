# Release Ladder and FOSS Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `versionCode`-last-digit publish routing with a three-rung branch ladder in which exactly one branch uploads to Play, and fix the FOSS-store defects that ladder exposes.

**Architecture:** `dev` uploads an AAB to Play `alpha`; `master` promotes `alpha` → `beta`; `production` promotes `beta` → `production`. All three rungs build their own APKs from their own commit, and every GitHub release tag is pinned to the commit that produced it. Decision logic moves out of inline YAML `run:` blocks into `.github/scripts/*.sh` and `fastlane/lib/thor_release.rb`, both of which get real unit tests — the workflows become thin callers of a single reusable workflow.

**Tech Stack:** GitHub Actions (reusable workflows), Fastlane 2.237.0 (`supply` / `upload_to_play_store`), Ruby + minitest, Bash + ShellCheck, actionlint, Gradle 9.6.1 / AGP 9.4.0-alpha07, Astro + Vitest (the `web/` site's repo-facts contract).

**Design spec:** `docs/superpowers/specs/2026-08-06-release-ladder-and-foss-distribution-design.md`

---

## Global Constraints

Every task's requirements implicitly include this section.

- **Never add a `Co-Authored-By: Claude` trailer to any commit.** This overrides any global instruction that says to.
- **Never commit directly to `dev`, `master` or `production`.** Branch, then open a PR into `dev`. Merge convention is `gh pr merge --merge`.
- **`master`, `dev` and `production` are the only permanent branches. Never delete `production`.**
- **Never run `git add -A` or `git add .`** — always stage explicit paths. `docs/audit/` must never be committed, and `docs/discussions/` is currently untracked and must stay that way.
- **The `codeberg` remote and `docs/enforcement/` are do-not-push.**
- **Gradle runs through `ctx_execute` with `language: "shell"`, never through Bash.** Unit tests need `--rerun-tasks` or they report `UP-TO-DATE` and silently skip.
- **The shell is zsh: quote every glob.** An unquoted `grep --include=*.kt` returns empty with exit 0 and no stderr. Use `git grep -n "PAT" -- 'path/*.kt'` or `rg -g '*.kt'`. `\b` is a no-op under BSD grep.
- **`versionCode` in `gradle.properties` changes only in a `chore(release)` commit.** `versionName` is derived (`code/1000 . code%1000/10 . code%10`) and is never edited directly.
- **Write files only with the native Write/Edit tools** — never via `ctx_execute`, `ctx_execute_file`, or Bash heredocs.
- Current values at plan time: `versionCode=1940` (v1.94.0), `agp = 9.4.0-alpha07`, `gradle = 9.6.1`, `kotlin = 2.4.10`, `compileSdk = 37`, `targetSdk = 37`, `minSdk = 28`, fastlane `2.237.0`.
- Repository default branch is **`master`**. Repository is `trinadhthatakula/Thor`. Application id is `com.valhalla.thor`.

---

## File Structure

### Created

| Path | Responsibility |
|---|---|
| `.github/scripts/detect-version-bump.sh` | Compare `versionCode` between two git refs; emit `changed`, `code`, `name`. The `dev` rung's trigger condition. |
| `.github/scripts/check-notes-budget.sh` | Pre-flight size gate: assembled Telegram caption ≤ 1024 UTF-16 units, `playstore.txt` < 500 chars. |
| `.github/scripts/test/test-detect-version-bump.sh` | Unit tests for the above, using a throwaway git repo in `mktemp -d`. |
| `.github/scripts/test/test-check-notes-budget.sh` | Unit tests for the notes budget gate. |
| `.github/scripts/test/run-tests.sh` | Runs every `test-*.sh`; single entry point for CI and humans. |
| `.github/workflows/release-rung.yml` | Reusable workflow holding all build/sign/publish machinery. Rung behaviour is entirely input-driven. |
| `.github/workflows/1-dev-publish.yml` | Caller: push to `dev` where `versionCode` changed → rung `dev`. |
| `.github/workflows/2-master-promote.yml` | Caller: push to `master` → rung `beta`. |
| `.github/workflows/3-production-promote.yml` | Caller: push to `production` → rung `production`. |
| `.github/scripts/test/test-runner-selfcheck.sh` | Proves the runner fails when a test fails — a runner that always passes is worse than none. |
| `.github/scripts/test/test-release-tag-pinning.sh` | Asserts every `action-gh-release` step sets `target_commitish`. |
| `.github/scripts/test/test-release-assets.sh` | Asserts no rung attaches an `.aab` to a GitHub release. |
| `.github/scripts/test/test-changelog-locale-parity.sh` | Asserts every metadata locale has a changelog for every code `en-US` has. |
| `.github/scripts/test/test-fastlane-lib.sh` | Bridges the Ruby minitest suite into the shell runner. |
| `.github/scripts/test/test-shizu-version-source.sh` | Asserts both Shizu scripts read `versionCode` from `production`. |
| `.github/scripts/test/test-obtainium-config.sh` | Asserts the published Obtainium config matches the assets the rung attaches. |
| `fastlane/lib/thor_release.rb` | Pure Ruby: track allow-lists, promotion edges, version-code parsing, version-name arithmetic. No fastlane dependency, so it is unit-testable. |
| `fastlane/test/test_thor_release.rb` | minitest suite for the above. |
| `fastlane/metadata/android/hi-IN/changelogs/` | Hindi changelog files, mirroring `en-US` until translated. |
| `docs/obtainium.md` | The published Obtainium configuration and deep link. |
| `docs/izzyondroid-notes.md` | The rbtlog recipe changes the ladder introduces, written to be filed as-is on their tracker. |
| `docs/openapk-submission.md` | The OpenAPK listing values, sourced from the existing Play metadata. |

### Modified

| Path | Change |
|---|---|
| `app/build.gradle.kts:197` | Delete `versionNameSuffix = "-foss"`. |
| `fastlane/Fastfile` | `require` the lib; split the track guard into upload vs promote; add `promote_beta` and `promote_production` lanes. |
| `.github/workflows/pr-ci.yml` | Add an `actionlint` + `shellcheck` + script-tests job. |
| `.github/scripts/check-shizu-manifest.sh` | Read `versionCode` from `gradle.properties` on the `production` branch, not the branch under test. |
| `.github/scripts/sync-shizu-changelog.sh` | Same change — the file carries an explicit LOCKSTEP contract with the checker. |
| `web/src/lib/repo-facts/index.ts`, `types.ts`, `contract.test.ts` | Remove `fossVersionName`. |
| `web/src/pages/styleguide/[...slug].astro:63` | Remove `fossVersionName` from the displayed fact list. |
| `web/src/content/claims.mjs` | Update the two rules whose `rationale`/`source` cite `versionNameSuffix` on the foss flavour. |
| `docs/fdroid-submission.md` | Rewrite for the developer-signed route; drop the `-foss` reconciliation. |
| `release-notes/README.md` | Document the three-rung ladder; the digit rule is gone. |
| `README.md`, `web/` install page | Add the Obtainium deep link. |
| `web/src/lib/repo-facts/parse.ts:39-40` | Retarget the workflow filenames it parses; the two it names are deleted. |
| `docs/follow-ups/README.md` | Drop the two retired rows, and re-verify every remaining row against its linked doc. |
| `CLAUDE.md` | Add a Release routing section — the routing is the thing most likely to be got wrong and it is currently documented nowhere in-repo. |

### Deleted

| Path | Reason |
|---|---|
| `.github/workflows/dev-check.yml` | Superseded by `1-dev-publish.yml` + `release-rung.yml`. Its digit gate is the thing being removed. |
| `.github/workflows/production-deploy.yml` | Superseded by `3-production-promote.yml` + `release-rung.yml`. |
| `docs/follow-ups/two-branches-one-play-version-code.md` | The collision it documents cannot occur with one uploader. Check inbound links first. |
| `docs/follow-ups/telegram-caption-length-guard.md` | Implemented as `check-notes-budget.sh`. Check inbound links first. |

### Deliberately unchanged

- `shizu_store.json` stays on `master`. Moving it would require re-registering the live raw URL with Shizu Store for no functional gain.
- `vcsInfo` stays enabled (no block is added to any Gradle file). The embedded commit sha is wanted.
- The tag suffix stays `github.run_number`, not the version code.
- `app/baselineprofile/` stays out of `settings.gradle.kts`.

---

## Phase 1 — Isolated fixes

Each task in this phase is independently shippable and changes no publish routing. Phase 1 can merge to `dev` and ride the existing pipeline before Phase 2 begins.

---

### Task 1: Static-analysis gate for workflows and shell scripts

This task exists first because it is the test harness every later workflow task depends on. `.github/workflows/dev-check.yml:258` records the gap in the repo's own words: *"there is no actionlint or shellcheck over .github/workflows."*

**Files:**
- Create: `.github/scripts/test/run-tests.sh`
- Create: `.github/scripts/test/test-runner-selfcheck.sh`
- Modify: `.github/workflows/pr-ci.yml` (add a `static-analysis` job)

**Pinning convention:** this repo pins third-party actions by SHA (`ruby/setup-ruby`,
`softprops/action-gh-release`) but uses tags for GitHub's own (`actions/checkout@v7`,
`actions/setup-java@v5.6.0`). Follow that convention rather than introducing a third — every new
step in this plan that uses an `actions/*` action should use the tag form already in the repo.

**Interfaces:**
- Consumes: nothing.
- Produces: `.github/scripts/test/run-tests.sh` — executable, exit 0 on pass, non-zero on failure. Later tasks drop `test-*.sh` files into `.github/scripts/test/` and they are picked up automatically. A CI job named `static-analysis` that later tasks rely on to catch YAML and shell errors.

- [ ] **Step 1: Write the test runner**

Create `.github/scripts/test/run-tests.sh`:

```bash
#!/usr/bin/env bash
# Runs every test-*.sh in this directory.
#
# Each test script is self-contained: it creates its own fixtures under a
# mktemp -d, cleans up on exit, prints one line per assertion, and exits
# non-zero on the first failure. A test file that exits 0 having asserted
# nothing is indistinguishable from a passing one, so every test script
# prints its assertion count and this runner checks the total is non-zero.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
failed=0
ran=0

shopt -s nullglob
for t in "$here"/test-*.sh; do
  ran=$((ran + 1))
  printf '\n=== %s ===\n' "$(basename "$t")"
  if bash "$t"; then
    printf '    PASS\n'
  else
    printf '    FAIL\n'
    failed=$((failed + 1))
  fi
done

if [ "$ran" -eq 0 ]; then
  echo "ERROR: no test-*.sh found in $here — a run that tests nothing is not a pass." >&2
  exit 1
fi

printf '\n%d test file(s), %d failed\n' "$ran" "$failed"
[ "$failed" -eq 0 ]
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/run-tests.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL — `ERROR: no test-*.sh found` with exit 1. This proves the "zero tests is not a pass" guard fires, which is the failure mode that would otherwise make every later task's green run meaningless.

- [ ] **Step 3: Add a temporary self-test so the runner has something to run**

Create `.github/scripts/test/test-runner-selfcheck.sh`:

```bash
#!/usr/bin/env bash
# Proves run-tests.sh discovers and executes test files. Kept permanently:
# if discovery ever breaks, every other suite silently reports success.
set -euo pipefail
assertions=0

[ -x "$(dirname "${BASH_SOURCE[0]}")/run-tests.sh" ] || {
  echo "  run-tests.sh is not executable"; exit 1; }
assertions=$((assertions + 1))
echo "  ok: run-tests.sh is executable"

echo "  ${assertions} assertion(s)"
```

- [ ] **Step 4: Run it to verify it passes**

```bash
chmod +x .github/scripts/test/test-runner-selfcheck.sh
.github/scripts/test/run-tests.sh
```

Expected: PASS — `1 test file(s), 0 failed`.

- [ ] **Step 5: Add the static-analysis job to `pr-ci.yml`**

Insert as a new top-level job under `jobs:` in `.github/workflows/pr-ci.yml`, sibling to the existing `shizu-manifest` job (which begins at `.github/workflows/pr-ci.yml:148`). Match the surrounding indentation and the repo's existing convention of pinning actions by commit SHA.

```yaml
  static-analysis:
    name: static-analysis
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8 # v5.0.0

      # Pinned by version AND checksum. `bash <(curl ...)`, which the upstream
      # README suggests, executes whatever the URL serves at that moment.
      - name: Install actionlint
        env:
          ACTIONLINT_VERSION: 1.7.7
          ACTIONLINT_SHA256: 023070a287cd8cccd71515fedc843f1985bf96c436b7effaecce67290e7e0757
        run: |
          set -euo pipefail
          url="https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz"
          curl --fail --silent --show-error --location -o actionlint.tar.gz "$url"
          echo "${ACTIONLINT_SHA256}  actionlint.tar.gz" | sha256sum --check --strict
          tar -xzf actionlint.tar.gz actionlint
          chmod +x actionlint

      - name: Lint workflows
        run: ./actionlint -color

      # shellcheck is preinstalled on ubuntu-latest runners.
      - name: Lint shell scripts
        run: |
          set -euo pipefail
          shellcheck --version
          # -x follows `source`d files; -S style is the strictest useful level.
          find .github/scripts -name '*.sh' -print0 | xargs -0 shellcheck -x -S style

      - name: Run shell script tests
        run: .github/scripts/test/run-tests.sh
```

- [ ] **Step 6: Verify the pinned checksum is correct before committing**

```bash
curl --fail --silent --location -o /tmp/al.tar.gz \
  https://github.com/rhysd/actionlint/releases/download/v1.7.7/actionlint_1.7.7_linux_amd64.tar.gz
shasum -a 256 /tmp/al.tar.gz
```

Expected: the digest printed must equal the `ACTIONLINT_SHA256` value in Step 5. **If it does not, replace the value in the workflow with the digest you just measured** — do not proceed with a mismatched pin, and do not remove the checksum check. Then `rm /tmp/al.tar.gz`.

- [ ] **Step 7: Run actionlint and shellcheck locally against the current tree**

```bash
brew install actionlint shellcheck
actionlint -color
find .github/scripts -name '*.sh' -print0 | xargs -0 shellcheck -x -S style
```

Expected: findings against the **existing** `dev-check.yml`, `production-deploy.yml`, and the two shizu scripts are likely. Fix only what is a genuine error in files this plan already touches. For pre-existing style findings in files this plan does not touch, add a targeted `# shellcheck disable=SCxxxx` with a one-line reason, or narrow the `-S` level to `warning` — record which you chose in the commit message. Do not silence a finding you have not read.

- [ ] **Step 8: Commit**

```bash
git add .github/scripts/test/run-tests.sh \
        .github/scripts/test/test-runner-selfcheck.sh \
        .github/workflows/pr-ci.yml
git commit -m "ci: add actionlint, shellcheck and a shell test runner

dev-check.yml:258 recorded that nothing lints .github/workflows. This adds
that gate plus a discovery-based runner for .github/scripts tests, so the
workflow rewrite that follows has something that can fail.

The runner treats zero discovered test files as an error: a suite that
tests nothing is otherwise indistinguishable from a passing one.

actionlint is pinned by version and sha256 rather than curl-piped."
```

---

### Task 2: Pin release tags to the commit that was built

**Files:**
- Create: `.github/scripts/test/test-release-tag-pinning.sh`
- Modify: `.github/workflows/production-deploy.yml:188-201`
- Modify: `.github/workflows/dev-check.yml:288-302`

**Interfaces:**
- Consumes: the `static-analysis` job from Task 1.
- Produces: nothing consumed by later tasks. `release-rung.yml` (Task 10) carries the same field forward; this task fixes the workflows that exist today so the repair ships without waiting for Phase 2.

**Why:** AGP emits `META-INF/version-control-info.textproto` by default in release builds, so every release APK carries its build commit's sha. IzzyOnDroid's `rbtlog` clones at the tag and asserts `git rev-parse HEAD == APP_COMMIT` before rebuilding. The GitHub API's documented default for `target_commitish` is *the repository's default branch* (`master`), so the tag currently lands on the built commit only because `production` has never carried a commit of its own. The first merge commit on `production` breaks every stable's reproducibility.

- [ ] **Step 1: Write the failing test**

Create `.github/scripts/test/test-release-tag-pinning.sh`:

```bash
#!/usr/bin/env bash
# Every softprops/action-gh-release step must pin target_commitish, or the
# tag is created on the repository default branch instead of the commit that
# was built - which silently breaks IzzyOnDroid reproducibility, because the
# APK embeds its build commit sha in META-INF/version-control-info.textproto.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, yaml

root = pathlib.Path(sys.argv[1])
bad = []
checked = 0

for wf in sorted((root / ".github" / "workflows").glob("*.yml")):
    doc = yaml.safe_load(wf.read_text())
    if not isinstance(doc, dict):
        continue
    for job_name, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in (job.get("steps") or []):
            uses = str(step.get("uses", ""))
            if not uses.startswith("softprops/action-gh-release@"):
                continue
            checked += 1
            with_ = step.get("with") or {}
            if "target_commitish" not in with_:
                bad.append(f"{wf.name}:{job_name}:{step.get('name', '<unnamed>')}")

if checked == 0:
    sys.exit("  no softprops/action-gh-release steps found - the test is vacuous")
if bad:
    sys.exit("  missing target_commitish:\n    " + "\n    ".join(bad))
print(f"  ok: all {checked} release step(s) pin target_commitish")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/test-release-tag-pinning.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL with `missing target_commitish:` listing both `dev-check.yml` and `production-deploy.yml`. If it instead reports "the test is vacuous", PyYAML failed to parse a workflow — fix that before continuing, because a vacuous test passes forever.

- [ ] **Step 3: Add the field to both release steps**

In `.github/workflows/production-deploy.yml`, in the `Create GitHub Release` step's `with:` block, immediately after `tag_name:`:

```yaml
          tag_name: v${{ steps.prep_notes.outputs.version_name }}
          # Anchor the tag to the commit we built. Without this the API
          # defaults to the repository default branch (master), and the APK's
          # embedded version-control-info sha stops matching the tag - which
          # is what IzzyOnDroid's rbtlog rebuilds against.
          target_commitish: ${{ github.sha }}
```

In `.github/workflows/dev-check.yml`, in the `Create GitHub Pre-Release` step's `with:` block, immediately after `tag_name:`:

```yaml
          tag_name: v${{ steps.prep_notes.outputs.version_name }}-dev-${{ github.run_number }}
          target_commitish: ${{ github.sha }}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
.github/scripts/test/run-tests.sh
actionlint -color
```

Expected: `ok: all 2 release step(s) pin target_commitish`, and actionlint clean.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/production-deploy.yml \
        .github/workflows/dev-check.yml \
        .github/scripts/test/test-release-tag-pinning.sh
git commit -m "ci: pin release tags to the commit that was built

Release APKs embed their build commit in
META-INF/version-control-info.textproto (AGP VcsInfo, on by default), and
IzzyOnDroid's rbtlog clones at the tag and asserts HEAD == APP_COMMIT
before rebuilding. The GitHub API defaults target_commitish to the
repository default branch, so today the tag lands on the built commit only
because production has never carried a commit of its own - verified:
'git log origin/production --not origin/master' is empty, and every stable
tag points at a 'Merge pull request #N from .../dev' commit.

Izzy has already hand-repaired this twice, with git reset --soft on
Thor_v1702 and Thor_v1706.

Guarded by a test that parses every workflow and fails if any
softprops/action-gh-release step omits the field."
```

---

### Task 3: Drop the AAB from the production release assets

**Files:**
- Create: `.github/scripts/test/test-release-assets.sh`
- Modify: `.github/workflows/production-deploy.yml:194-197`

**Interfaces:**
- Consumes: Task 1's `static-analysis` job.
- Produces: a two-asset release shape (`foss-release.apk`, `store-release.apk`) that Task 14's Obtainium `apkFilterRegEx` and the IzzyOnDroid `ApkMatch` rely on.

**Why:** the AAB is not installable, and Play re-signs it with Google's app-signing key, so it is not the artifact any user receives. It is one more thing every store client's asset filter must step past.

- [ ] **Step 1: Write the failing test**

Create `.github/scripts/test/test-release-assets.sh`:

```bash
#!/usr/bin/env bash
# The public release pages must offer only installable artifacts. An .aab on
# a page users browse gets downloaded and filed as a bug, and every store
# client (Obtainium apkFilterRegEx, IzzyOnDroid ApkMatch) has to filter past
# it.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
assertions=0

python3 - "$repo_root" <<'PY'
import sys, pathlib, yaml

root = pathlib.Path(sys.argv[1])
checked = 0
bad = []

for wf in sorted((root / ".github" / "workflows").glob("*.yml")):
    doc = yaml.safe_load(wf.read_text())
    if not isinstance(doc, dict):
        continue
    for job_name, job in (doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in (job.get("steps") or []):
            if not str(step.get("uses", "")).startswith("softprops/action-gh-release@"):
                continue
            files = str((step.get("with") or {}).get("files", ""))
            checked += 1
            for line in files.splitlines():
                line = line.strip()
                if line.endswith(".aab") or line.endswith("*.aab"):
                    bad.append(f"{wf.name}:{job_name}: {line}")

if checked == 0:
    sys.exit("  no softprops/action-gh-release steps found - the test is vacuous")
if bad:
    sys.exit("  .aab published as a release asset:\n    " + "\n    ".join(bad))
print(f"  ok: {checked} release step(s) publish no .aab")
PY

assertions=$((assertions + 1))
echo "  ${assertions} assertion(s)"
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/test-release-assets.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL — `.aab published as a release asset: production-deploy.yml:...: app/build/outputs/bundle/storeRelease/*.aab`

- [ ] **Step 3: Remove the AAB line**

In `.github/workflows/production-deploy.yml`, the `files:` block of the `Create GitHub Release` step becomes exactly:

```yaml
          files: |
            app/build/distribution/foss/foss-release.apk
            app/build/distribution/store/store-release.apk
```

Leave the AAB *build* alone — `bundleStoreRelease` still runs, because Play needs it. Only the release asset is dropped.

- [ ] **Step 4: Run the test to verify it passes**

```bash
.github/scripts/test/run-tests.sh
```

Expected: `ok: 2 release step(s) publish no .aab`

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/production-deploy.yml \
        .github/scripts/test/test-release-assets.sh
git commit -m "ci: stop publishing the AAB as a GitHub release asset

An .aab is not installable, and Play re-signs the bundle with Google's
app-signing key, so the file on the release page is not what any user
receives. It is also one more asset that Obtainium's apkFilterRegEx and
IzzyOnDroid's ApkMatch have to filter past, and a filter that gets it wrong
points someone at an unusable file.

bundleStoreRelease still builds; only the published asset is removed."
```

---

### Task 4: Fix the blank Hindi changelogs

**Files:**
- Create: `fastlane/metadata/android/hi-IN/changelogs/1931.txt`, `1932.txt`, `1933.txt`, `1940.txt`
- Create: `.github/scripts/test/test-changelog-locale-parity.sh`

**Interfaces:**
- Consumes: Task 1's test runner.
- Produces: locale parity that Task 7's `promote_production` lane relies on when it uploads curated notes.

**Why:** `fastlane/metadata/android/hi-IN/` holds `full_description.txt`, `short_description.txt` and `title.txt` but **no `changelogs/` directory**. `supply` enumerates locales from the metadata directory, so `hi-IN` is included in every changelog upload; with no file to read, `changelog_text` falls through to `''` and a `LocalizedText` is emitted anyway. **This already blanks the Hindi what's-new on every release** — it is not introduced by the ladder.

`en-US/changelogs/` currently holds `1600.txt`, `1931.txt`, `1932.txt`, `1933.txt`, `1940.txt`. `1600.txt` is an orphan from a much older code and is deliberately **not** mirrored.

- [ ] **Step 1: Write the failing test**

Create `.github/scripts/test/test-changelog-locale-parity.sh`:

```bash
#!/usr/bin/env bash
# supply enumerates locales from the metadata directory, so a locale with no
# changelogs/ dir still gets a LocalizedText - with empty text. That blanks
# the what's-new for those users rather than leaving the previous one. Every
# locale that has metadata must therefore have a changelogs dir, and every
# changelog present in the reference locale must be present in all of them.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
meta="$repo_root/fastlane/metadata/android"
reference="en-US"
# 1600 predates the current release-notes tree and has no counterpart. It is
# kept for history and deliberately not mirrored.
exempt="1600.txt"
assertions=0

[ -d "$meta/$reference/changelogs" ] || { echo "  reference locale has no changelogs dir"; exit 1; }

ref_files="$(cd "$meta/$reference/changelogs" && ls -1 ./*.txt | sed 's|^\./||' | sort)"
[ -n "$ref_files" ] || { echo "  reference locale has no changelog files - vacuous"; exit 1; }

failed=0
for dir in "$meta"/*/; do
  locale="$(basename "$dir")"
  [ "$locale" = "$reference" ] && continue
  # A locale directory with no metadata at all is not a locale supply sees.
  ls "$dir"/*.txt >/dev/null 2>&1 || continue

  if [ ! -d "$dir/changelogs" ]; then
    echo "  MISSING: $locale has metadata but no changelogs/ dir"
    failed=1
    continue
  fi

  for f in $ref_files; do
    case " $exempt " in *" $f "*) continue ;; esac
    assertions=$((assertions + 1))
    if [ ! -f "$dir/changelogs/$f" ]; then
      echo "  MISSING: $locale/changelogs/$f"
      failed=1
    elif [ ! -s "$dir/changelogs/$f" ]; then
      echo "  EMPTY:   $locale/changelogs/$f"
      failed=1
    fi
  done
done

[ "$failed" -eq 0 ] || exit 1
echo "  ok: every locale mirrors $reference's changelogs"
echo "  ${assertions} assertion(s)"
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/test-changelog-locale-parity.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL — `MISSING: hi-IN has metadata but no changelogs/ dir`

- [ ] **Step 3: Create the Hindi changelog files**

Create `fastlane/metadata/android/hi-IN/changelogs/` and, for each of `1931.txt`, `1932.txt`, `1933.txt`, `1940.txt`, copy the **exact byte content** of the `en-US` file of the same name.

Read each source file and write its content verbatim with the Write tool:

```
fastlane/metadata/android/en-US/changelogs/1931.txt -> fastlane/metadata/android/hi-IN/changelogs/1931.txt
fastlane/metadata/android/en-US/changelogs/1932.txt -> fastlane/metadata/android/hi-IN/changelogs/1932.txt
fastlane/metadata/android/en-US/changelogs/1933.txt -> fastlane/metadata/android/hi-IN/changelogs/1933.txt
fastlane/metadata/android/en-US/changelogs/1940.txt -> fastlane/metadata/android/hi-IN/changelogs/1940.txt
```

English text under a Hindi locale is not ideal, but it is strictly better than a blank what's-new, and `supply` offers no per-language changelog skip. When translations arrive they replace these files with no structural change.

- [ ] **Step 4: Run the test to verify it passes**

```bash
.github/scripts/test/run-tests.sh
```

Expected: `ok: every locale mirrors en-US's changelogs` with 4 assertions.

- [ ] **Step 5: Verify the copies are byte-identical**

```bash
for f in 1931 1932 1933 1940; do
  if cmp -s "fastlane/metadata/android/en-US/changelogs/$f.txt" \
            "fastlane/metadata/android/hi-IN/changelogs/$f.txt"; then
    echo "identical: $f.txt"
  else
    echo "DIFFERS:   $f.txt"
  fi
done
```

Expected: four `identical:` lines.

- [ ] **Step 6: Commit**

```bash
git add fastlane/metadata/android/hi-IN/changelogs \
        .github/scripts/test/test-changelog-locale-parity.sh
git commit -m "fix(fastlane): stop blanking the Hindi what's-new on every release

fastlane/metadata/android/hi-IN/ had full_description, short_description
and title but no changelogs/ dir. supply enumerates locales from the
metadata directory, so hi-IN was included in every changelog upload,
changelog_text fell through to '' and a LocalizedText was emitted anyway -
setting the Hindi release notes to empty rather than leaving the previous
ones.

Mirrors the en-US text until translations exist; supply has no
per-language changelog skip, and English notes beat none. 1600.txt is an
orphan from an older code and is deliberately not mirrored.

Guarded by a locale-parity test."
```

---

### Task 5: Drop the `-foss` versionName suffix

**Files:**
- Modify: `app/build.gradle.kts:197`
- Modify: `web/src/lib/repo-facts/index.ts:42-43`
- Modify: `web/src/lib/repo-facts/types.ts:21`
- Modify: `web/src/lib/repo-facts/contract.test.ts:30`
- Modify: `web/src/pages/styleguide/[...slug].astro:63`
- Modify: `web/src/content/claims.mjs` (the rules whose `rationale`/`source` cite the suffix)

**Interfaces:**
- Consumes: nothing.
- Produces: `foss` and `store` both report versionName `1.94.0`. Task 14's Obtainium config and Task 16's F-Droid `Binaries:` URL both depend on this.

**Why, twice over:**

1. **Obtainium.** `1.94.0-foss` matches none of Obtainium's strict version patterns — its recognised suffix list is `alpha|beta|rc|pre|dev|snapshot|nightly|ose|[0-9]+`, with no `foss`. Reconciliation returns null, Obtainium sets `versionDetection = false` and stamps `installedVersion = latestVersion`. A user on an outdated foss build is marked **up to date and never offered the update**. Task 14 pins `apkFilterRegEx` to `foss-release.apk`, which would move every user *into* that broken state — so this task must land first.
2. **F-Droid.** `Binaries:` substitutes only `%v` and `%c`. With the suffix, `%v` is `1.94.0-foss` and the URL resolves to a tag that does not exist. No substitution can repair it.

`versionCode` is untouched, so no user sees a downgrade. The cost is that `foss` and `store` are no longer distinguishable in Settings → App info; that was accepted in the design (decision 8).

- [ ] **Step 1: Run the existing web contract test to see it currently passes**

```bash
cd web && npm ci && npx vitest run src/lib/repo-facts/contract.test.ts
```

Expected: PASS. `web/src/lib/repo-facts/contract.test.ts:30` asserts
`expect(facts.fossVersionName).toBe(\`${facts.versionName}-foss\`)`. This is the
existing test that the Gradle change must invalidate — it is the anchor for this task.

- [ ] **Step 2: Make the Gradle change**

In `app/build.gradle.kts`, the `foss` flavour block becomes:

```kotlin
        create("foss") {
            dimension = "distribution"
            // No versionNameSuffix on purpose. Obtainium cannot reconcile
            // "1.94.0-foss" against tag v1.94.0 - "foss" is not in its
            // recognised suffix list - so it sets versionDetection = false and
            // marks outdated users as up to date. F-Droid's Binaries: field
            // also substitutes only %v, which would resolve to a tag that does
            // not exist. The flavour is identified by its APK filename
            // (foss-release.apk) and its ProGuard file, not by versionName.
            proguardFile("proguard-rules-foss.pro")
        }
```

Leave the `-benchmark` suffix on the benchmark build type alone (`app/build.gradle.kts:182`) — that one is unrelated and still wanted.

- [ ] **Step 3: Verify the built APK reports the bare version name**

```bash
./gradlew -q app:printVersionName
```

Expected: `1.94.0`

Then build and read the APK's actual manifest — `printVersionName` reports the base name and would not have caught the suffix either way, so the APK itself is the real check:

```bash
./gradlew assembleFossRelease
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump badging \
  app/build/outputs/apk/foss/release/*.apk | grep -o "versionName='[^']*'"
```

Expected: `versionName='1.94.0'` — with no `-foss`. Run the Gradle commands through `ctx_execute` with `language: "shell"`, per the global constraints.

- [ ] **Step 4: Run the web test to verify it now fails**

```bash
cd web && npx vitest run src/lib/repo-facts/contract.test.ts
```

Expected: FAIL. `fossVersionName` still returns `1.94.0-foss` from a hardcoded template literal, which no longer describes any artifact the build produces. This is the fallout the Gradle change creates.

- [ ] **Step 5: Remove `fossVersionName` from the web repo-facts contract**

In `web/src/lib/repo-facts/index.ts`, delete both the comment and the property from the returned frozen object:

```ts
  return Object.freeze({
    versionCode,
    versionName,
    minSdk,
```

In `web/src/lib/repo-facts/types.ts`, delete the line:

```ts
  readonly fossVersionName: string
```

In `web/src/lib/repo-facts/contract.test.ts`, delete the assertion at line 30 and replace it with one that pins the new invariant:

```ts
    // The foss flavour no longer carries a versionNameSuffix, so both
    // flavours report the same versionName. Asserted rather than dropped:
    // re-adding a suffix silently breaks Obtainium's version detection and
    // F-Droid's Binaries: URL.
    expect(facts).not.toHaveProperty('fossVersionName')
```

In `web/src/pages/styleguide/[...slug].astro`, remove `'fossVersionName',` from the fact-name list at line 63.

- [ ] **Step 6: Update the two claims whose evidence cites the suffix**

In `web/src/content/claims.mjs`, the rule near line 178 has a `rationale` and `source` naming `versionNameSuffix` as evidence that the flavours differ in more than one respect. The claim itself stays true — the flavours still differ — but its cited evidence must change. Replace the suffix mention in both strings with the ProGuard file and the store-only benchmark build type, which remain real differences:

- In `rationale`, replace `'One *functional* difference, several others. The foss flavour also sets a `-foss` versionNameSuffix and adds proguard-rules-foss.pro, and the benchmark build type is created only for store.'` with wording that drops the suffix clause and keeps `proguard-rules-foss.pro` and the store-only benchmark build type.
- In `source`, replace `productFlavors.create("foss") (versionNameSuffix, proguardFile("proguard-rules-foss.pro"))` with `productFlavors.create("foss") (proguardFile("proguard-rules-foss.pro"))`.

Apply the same edit to the rule near line 368, whose `source` reads `the store/foss productFlavors blocks set only dimension, versionNameSuffix and a ProGuard file` — it becomes `set only dimension and a ProGuard file`.

Read each rule in full before editing. These strings are rule metadata, not matched patterns, so a stale one fails no gate — which is exactly why it has to be caught here.

- [ ] **Step 7: Run the full web build and test suite**

```bash
cd web && npm run check:types && npm test && npm run build
```

Expected: PASS on all three. `npm run build` chains `check:links`, `check:claims`, `check:markup`, `check:sitemap` and `check:screenshots`; a failure in `check:claims` means a page's prose still asserts the suffix and must be corrected too.

- [ ] **Step 8: Confirm no tracked file still asserts the suffix**

```bash
cd .. && git grep -n -- 'versionNameSuffix = "-foss"'
git grep -n 'fossVersionName'
```

Expected: no results from either, except inside `docs/superpowers/specs/`, `docs/superpowers/plans/` and `docs/follow-ups/`, which are historical records and are left alone. `docs/fdroid-submission.md` will still match; it is rewritten in Task 16.

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts \
        web/src/lib/repo-facts/index.ts \
        web/src/lib/repo-facts/types.ts \
        web/src/lib/repo-facts/contract.test.ts \
        web/src/pages/styleguide/'[...slug]'.astro \
        web/src/content/claims.mjs
git commit -m "fix(build): drop the -foss versionName suffix

'1.94.0-foss' matches none of Obtainium's recognised version suffixes
(alpha|beta|rc|pre|dev|snapshot|nightly|ose|digits), so reconciliation
returns null, versionDetection is set to false and installedVersion is
stamped equal to latestVersion. A user on an outdated foss build is marked
up to date and never offered the update. Pinning Obtainium to
foss-release.apk - which is the point of the store work - would move every
user into that state, so the suffix has to go first.

It also made the F-Droid Binaries: URL inexpressible: that field
substitutes only %v and %c, so %v would resolve to tag v1.94.0-foss, which
does not exist.

versionCode is untouched, so nobody sees a downgrade. The flavour is
identified by its APK filename and ProGuard file instead.

web/ carried the suffix in three places - a hardcoded fossVersionName in
repo-facts, its type, its contract test, the styleguide fact list, and two
claims rules citing it as evidence. All updated."
```

---

## Phase 2 — The ladder

Phase 2 changes publish routing. Nothing here is safe to merge piecemeal to `dev` unless the whole
phase lands together, because Task 11 deletes the workflows that Tasks 1–5 patched. Do the whole
phase on one branch, open one PR.

---

### Task 6: `thor_release.rb` — the pure logic behind the ladder

**Files:**
- Create: `fastlane/lib/thor_release.rb`
- Create: `fastlane/test/test_thor_release.rb`
- Create: `.github/scripts/test/test-fastlane-lib.sh`

**Interfaces:**
- Consumes: nothing.
- Produces, all consumed by Task 7's Fastfile lanes:
  - `ThorRelease::UPLOAD_TRACKS` → `Array<String>`
  - `ThorRelease::PROMOTION_EDGES` → `Hash{String => String}` mapping destination track to required source track
  - `ThorRelease.validate_upload_track!(track) -> String` (raises `ThorRelease::Error`)
  - `ThorRelease.source_track_for(destination) -> String` (raises `ThorRelease::Error`)
  - `ThorRelease.version_code_from(properties_path) -> Integer` (raises `ThorRelease::Error`)
  - `ThorRelease.version_name_for(code) -> String`
  - `ThorRelease.assert_code_present!(code:, track:, codes_in_track:) -> true` (raises `ThorRelease::Error`)

**Why a separate file:** none of this needs fastlane loaded, so it can be unit-tested with plain
minitest — which ships with Ruby, so no Gemfile change. The Fastfile keeps only the parts that
genuinely need fastlane actions.

- [ ] **Step 1: Write the failing test**

Create `fastlane/test/test_thor_release.rb`:

```ruby
# frozen_string_literal: true

require 'minitest/autorun'
require 'tmpdir'
require_relative '../lib/thor_release'

class TestVersionArithmetic < Minitest::Test
  # versionName is derived as code/1000 . code%1000/10 . code%10 - the same
  # arithmetic as app/build.gradle.kts and check-shizu-manifest.sh. Three
  # independent implementations of one rule; this is the one with tests.
  def test_stable_code
    assert_equal '1.94.0', ThorRelease.version_name_for(1940)
  end

  def test_patch_code
    assert_equal '1.93.3', ThorRelease.version_name_for(1933)
  end

  def test_two_digit_minor
    assert_equal '1.90.8', ThorRelease.version_name_for(1908)
  end

  def test_minor_boundary
    assert_equal '1.9.9', ThorRelease.version_name_for(1099)
  end
end

class TestVersionCodeParsing < Minitest::Test
  def with_properties(contents)
    Dir.mktmpdir do |dir|
      path = File.join(dir, 'gradle.properties')
      File.write(path, contents)
      yield path
    end
  end

  def test_reads_the_code
    with_properties("org.gradle.jvmargs=-Xmx4g\nversionCode=1940\n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  # An unanchored match also finds initialVersionCode=1921, which is the bug
  # that made the old release-manager workflow unusable.
  def test_ignores_other_keys_containing_versioncode
    with_properties("initialVersionCode=1921\nversionCode=1940\n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  def test_tolerates_surrounding_whitespace
    with_properties("  versionCode = 1940  \n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  def test_ignores_a_commented_out_code
    with_properties("#versionCode=1234\nversionCode=1940\n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  def test_raises_when_absent
    with_properties("org.gradle.jvmargs=-Xmx4g\n") do |p|
      err = assert_raises(ThorRelease::Error) { ThorRelease.version_code_from(p) }
      assert_match(/versionCode/, err.message)
    end
  end

  def test_raises_when_file_missing
    assert_raises(ThorRelease::Error) { ThorRelease.version_code_from('/nope/gradle.properties') }
  end
end

class TestTrackGuards < Minitest::Test
  def test_alpha_is_uploadable
    assert_equal 'alpha', ThorRelease.validate_upload_track!('alpha')
  end

  def test_internal_is_uploadable
    assert_equal 'internal', ThorRelease.validate_upload_track!('internal')
  end

  # The whole point of the ladder: exactly one branch uploads. beta and
  # production are reached by promotion only, so an upload naming them is a
  # bug, not a shortcut.
  def test_beta_is_not_uploadable
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!('beta') }
  end

  def test_production_is_not_uploadable
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!('production') }
  end

  # upload_to_play_store CREATES a track by an unknown name rather than
  # failing, so a typo must be caught here.
  def test_typo_is_rejected
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!('alhpa') }
  end

  def test_strips_whitespace
    assert_equal 'alpha', ThorRelease.validate_upload_track!("  alpha\n")
  end

  def test_nil_is_rejected
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!(nil) }
  end
end

class TestPromotionEdges < Minitest::Test
  def test_beta_comes_from_alpha
    assert_equal 'alpha', ThorRelease.source_track_for('beta')
  end

  def test_production_comes_from_beta
    assert_equal 'beta', ThorRelease.source_track_for('production')
  end

  # A rung may only promote from the track directly below it. Skipping a rung
  # must be a red build, not a silent shortcut to production.
  def test_production_cannot_come_from_alpha
    refute_equal 'alpha', ThorRelease.source_track_for('production')
  end

  def test_alpha_is_not_a_promotion_destination
    assert_raises(ThorRelease::Error) { ThorRelease.source_track_for('alpha') }
  end

  def test_unknown_destination_is_rejected
    assert_raises(ThorRelease::Error) { ThorRelease.source_track_for('nonsense') }
  end
end

class TestCodePresenceAssertion < Minitest::Test
  def test_passes_when_present
    assert ThorRelease.assert_code_present!(code: 1940, track: 'alpha', codes_in_track: [1933, 1940])
  end

  def test_raises_when_absent
    err = assert_raises(ThorRelease::Error) do
      ThorRelease.assert_code_present!(code: 1941, track: 'alpha', codes_in_track: [1933, 1940])
    end
    assert_match(/1941/, err.message)
    assert_match(/alpha/, err.message)
  end

  # google_play_track_version_codes returns whatever the API gave, and an
  # empty array means "nothing in that track" - never "assume it is fine".
  def test_raises_on_empty_track
    assert_raises(ThorRelease::Error) do
      ThorRelease.assert_code_present!(code: 1940, track: 'beta', codes_in_track: [])
    end
  end

  # The API returns integers, but a code read from a file or an env var is a
  # string. Comparing them without coercion silently never matches.
  def test_coerces_string_codes
    assert ThorRelease.assert_code_present!(code: '1940', track: 'alpha', codes_in_track: ['1940'])
  end
end
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
ruby fastlane/test/test_thor_release.rb
```

Expected: FAIL — `cannot load such file -- .../fastlane/lib/thor_release` (LoadError).

- [ ] **Step 3: Write the implementation**

Create `fastlane/lib/thor_release.rb`:

```ruby
# frozen_string_literal: true

# Pure logic for Thor's three-rung release ladder.
#
# Deliberately free of any fastlane dependency so it can be unit-tested with
# plain minitest: `ruby fastlane/test/test_thor_release.rb`. Anything here that
# starts needing a fastlane action belongs in the Fastfile instead.
#
# The ladder: dev uploads to alpha, master promotes alpha -> beta, production
# promotes beta -> production. Exactly one branch ever uploads, which is what
# makes Play's per-app (not per-track) version-code uniqueness a non-issue.
module ThorRelease
  class Error < StandardError; end

  # Tracks an artifact may be UPLOADED to. beta and production are absent on
  # purpose - they are reached by promotion only. upload_to_play_store creates
  # a track by an unrecognised name rather than failing, so this list is the
  # only thing standing between a typo and a phantom track.
  UPLOAD_TRACKS = %w[internal alpha].freeze

  # destination => the track a build must already be in to be promoted there.
  # A rung may only promote from the track directly below it, so skipping a
  # rung fails the build instead of shipping an unreviewed build to users.
  PROMOTION_EDGES = {
    'beta' => 'alpha',
    'production' => 'beta'
  }.freeze

  def self.validate_upload_track!(track)
    normalised = track.to_s.strip
    unless UPLOAD_TRACKS.include?(normalised)
      raise Error, "upload track must be one of #{UPLOAD_TRACKS.join(', ')} - got #{track.inspect}. " \
                   'beta and production are promotion-only: exactly one branch uploads to Play.'
    end
    normalised
  end

  def self.source_track_for(destination)
    normalised = destination.to_s.strip
    PROMOTION_EDGES.fetch(normalised) do
      raise Error, "no promotion edge into #{destination.inspect}. " \
                   "Known destinations: #{PROMOTION_EDGES.keys.join(', ')}."
    end
  end

  # Anchored on purpose: an unanchored match also finds initialVersionCode,
  # which fed two lines into arithmetic and made the old release-manager
  # workflow unusable.
  VERSION_CODE_LINE = /^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$/.freeze

  def self.version_code_from(properties_path)
    unless File.file?(properties_path)
      raise Error, "gradle.properties not found at #{properties_path}"
    end

    File.readlines(properties_path).each do |line|
      next if line.lstrip.start_with?('#')

      match = VERSION_CODE_LINE.match(line)
      return match[1].to_i if match
    end

    raise Error, "no versionCode assignment found in #{properties_path}"
  end

  # Mirrors app/build.gradle.kts and .github/scripts/check-shizu-manifest.sh:
  # 1940 -> 1.94.0, 1933 -> 1.93.3. versionName is never stored, only derived.
  def self.version_name_for(code)
    n = code.to_i
    "#{n / 1000}.#{(n % 1000) / 10}.#{n % 10}"
  end

  # The invariant the whole ladder rests on: a rung may only promote a version
  # code that is already in the track below it. An empty track means "nothing
  # there", never "assume it is fine".
  def self.assert_code_present!(code:, track:, codes_in_track:)
    wanted = code.to_i
    present = Array(codes_in_track).map(&:to_i)

    unless present.include?(wanted)
      raise Error, "versionCode #{wanted} is not in the #{track} track " \
                   "(found: #{present.empty? ? 'nothing' : present.sort.join(', ')}). " \
                   'A rung may only promote a build the rung below it already published - ' \
                   'check that the lower rung actually ran.'
    end
    true
  end
end
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
ruby fastlane/test/test_thor_release.rb
```

Expected: PASS — `0 failures, 0 errors`, with 27 assertions or more across 5 test classes.

- [ ] **Step 5: Wire the Ruby tests into the shell test runner**

Create `.github/scripts/test/test-fastlane-lib.sh`:

```bash
#!/usr/bin/env bash
# Runs the Ruby unit tests for fastlane/lib so one runner covers every
# non-Gradle test in the repo.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

ruby "$repo_root/fastlane/test/test_thor_release.rb"
echo "  ok: fastlane/lib unit tests passed"
```

- [ ] **Step 6: Run the whole suite**

```bash
chmod +x .github/scripts/test/test-fastlane-lib.sh
.github/scripts/test/run-tests.sh
```

Expected: every test file passes, including the new one.

- [ ] **Step 7: Commit**

```bash
git add fastlane/lib/thor_release.rb \
        fastlane/test/test_thor_release.rb \
        .github/scripts/test/test-fastlane-lib.sh
git commit -m "feat(fastlane): add ThorRelease, the pure logic behind the ladder

Track allow-lists, promotion edges, versionCode parsing and versionName
arithmetic, with no fastlane dependency so it is unit-testable with plain
minitest - no Gemfile change needed.

Two guards worth naming:

UPLOAD_TRACKS excludes beta and production. upload_to_play_store creates a
track by an unrecognised name rather than failing, so this list is what
stands between a typo and a phantom track - and it is what enforces 'exactly
one branch uploads'.

PROMOTION_EDGES maps each destination to the track a build must already be
in. assert_code_present! treats an empty track as absent, never as fine.

versionCode parsing is anchored: an unanchored match also finds
initialVersionCode, which is the bug that made the old release-manager
workflow unusable."
```

---

### Task 7: Fastfile promote lanes

**Files:**
- Modify: `fastlane/Fastfile` — `require_relative` the lib, replace the guard at `:15-21`, add two lanes after `:150`

**Interfaces:**
- Consumes: every symbol listed in Task 6's Produces block.
- Produces, consumed by Task 10's reusable workflow:
  - lane `distribute_dev` — builds APKs + AAB, uploads the AAB to `alpha`. Unchanged in behaviour.
  - lane `promote_beta` — builds APKs, asserts the code is in `alpha`, promotes `alpha` → `beta`.
  - lane `promote_production` — builds APKs, asserts the code is in `beta`, promotes `beta` → `production`, uploads the curated `en-US` + `hi-IN` changelogs.
  - lane `build_release_candidates` — unchanged.
  - All lanes continue to write `version_name.txt`, `version_code.txt` and `track.txt` at the repo root.

**Two footguns this task exists to avoid:**

1. `upload_to_play_store` back-fills `params[:aab]` from `SharedValues::GRADLE_AAB_OUTPUT_PATH`
   whenever it is nil, and `skip_upload_aab` defaults to **`false`**. Because every rung now builds
   before promoting, `lane_context` is populated — a promote lane without **both**
   `skip_upload_apk: true` and `skip_upload_aab: true` uploads the AAB and dies on the duplicate
   code. That is the exact error this whole plan removes.
2. `promote_track` copies the source release object wholesale, so **without an explicit metadata
   pass, production inherits the dev auto-notes verbatim.** `promote_production` must set
   `skip_upload_changelogs: false` deliberately.

- [ ] **Step 1: Require the lib and replace the track guard**

At the very top of `fastlane/Fastfile`, above `default_platform(:android)`:

```ruby
require_relative 'lib/thor_release'

default_platform(:android)
```

Then replace the guard block at `fastlane/Fastfile:15-21` — the lines from `if upload_to_store`
through the closing `end` — with:

```ruby
    # A lane that uploads must name a track this repo is allowed to upload to.
    # beta and production are NOT on that list: they are reached by promotion
    # only, which is what gives every version code exactly one uploader and
    # makes Play's per-app code uniqueness a non-issue rather than an error to
    # handle. See ThorRelease::UPLOAD_TRACKS.
    #
    # This used to allow beta, because production promotion was a manual Play
    # Console action and that manual step was the release gate. Promotion is
    # automated now, so the guard gets stricter rather than looser.
    track = ThorRelease.validate_upload_track!(track) if upload_to_store
```

- [ ] **Step 2: Add the promotion helper and the two lanes**

Insert after the `distribute_production` lane ends at `fastlane/Fastfile:150`, before the
`build_release_candidates` lane:

```ruby
  # --- PROMOTION ---
  # Builds this rung's APKs from this rung's commit (needed for the GitHub
  # release and Telegram, and required for reproducibility: the APK embeds its
  # build commit sha in META-INF/version-control-info.textproto), then moves an
  # already-uploaded bundle one track up. Nothing here uploads an artifact.
  def promote_to_track(destination:, upload_changelogs: false)
    source = ThorRelease.source_track_for(destination)

    project_root = File.expand_path('..', Dir.pwd)
    code = ThorRelease.version_code_from(File.join(project_root, 'gradle.properties'))
    name = ThorRelease.version_name_for(code)

    UI.message("⬆️  Promoting v#{name} (#{code}): #{source} -> #{destination}")

    # Build the APKs for GitHub/Telegram. upload_to_store: false means no AAB
    # is built and no upload is attempted; it also writes version_name.txt and
    # version_code.txt, which the workflow reads.
    prepare_release_artifacts(upload_to_store: false, version_code: code)

    # prepare_release_artifacts wrote an empty track.txt because it did not
    # upload. The Telegram caption reads this file to name the track, so
    # overwrite it with where the build is actually going.
    File.write(File.join(project_root, 'track.txt'), destination)

    # The invariant: only promote what the rung below already published.
    # Asked as a yes/no assertion, never as "what is newest?" -
    # google_play_track_version_codes has no status filter and no ordering, and
    # promotion leaves the code active in the source track, so "newest in beta"
    # becomes a dev code after the first ladder run.
    codes = google_play_track_version_codes(track: source)
    ThorRelease.assert_code_present!(code: code, track: source, codes_in_track: codes)

    if upload_changelogs
      copy_playstore_notes(project_root: project_root, version_name: name, version_code: code)
    end

    upload_to_play_store(
      track: source,
      track_promote_to: destination,
      track_promote_release_status: 'completed',
      version_code: code,
      # BOTH skips are required. upload_to_play_store back-fills params[:aab]
      # from SharedValues::GRADLE_AAB_OUTPUT_PATH when it is nil and both flags
      # default to false, so a promote lane missing either one uploads the
      # bundle and dies on "version code already used".
      skip_upload_apk: true,
      skip_upload_aab: true,
      skip_upload_metadata: true,
      skip_upload_changelogs: !upload_changelogs,
      skip_upload_images: true,
      skip_upload_screenshots: true
    )

    UI.success("🚀 Promoted v#{name} (#{code}) to [#{destination}]")
  end

  # Copies the curated Play notes into the supply layout for EVERY locale that
  # has metadata. hi-IN has no translations yet and mirrors en-US: supply
  # enumerates locales from the metadata directory and emits a LocalizedText
  # with empty text for any locale it cannot find a file for, which blanks
  # those users' what's-new rather than leaving the previous one.
  def copy_playstore_notes(project_root:, version_name:, version_code:)
    require 'fileutils'

    notes = File.join(project_root, 'release-notes', "v#{version_name}", 'playstore.txt')
    notes = File.join(project_root, 'release-notes', version_name, 'playstore.txt') unless File.exist?(notes)
    UI.user_error!("❌ No curated playstore.txt for v#{version_name} - a stable must have one") unless File.exist?(notes)

    metadata_root = File.join(Dir.pwd, 'metadata', 'android')
    locales = Dir.children(metadata_root).select { |d| File.directory?(File.join(metadata_root, d)) }
    UI.user_error!("❌ No locales found under #{metadata_root}") if locales.empty?

    locales.each do |locale|
      dest_dir = File.join(metadata_root, locale, 'changelogs')
      FileUtils.mkdir_p(dest_dir)
      FileUtils.cp(notes, File.join(dest_dir, "#{version_code}.txt"))
      UI.message("📝 #{locale}/changelogs/#{version_code}.txt")
    end
  end

  desc 'Master: build -> promote alpha to beta (Open testing) -> GitHub pre-release'
  lane :promote_beta do
    # No changelog upload: the promotion carries alpha's notes forward, and the
    # curated notes are written once, at production.
    promote_to_track(destination: 'beta', upload_changelogs: false)
  end

  desc 'Production: build -> promote beta to production -> GitHub release'
  lane :promote_production do
    # promote_track copies the source release object wholesale, so without this
    # explicit pass production would inherit the dev auto-notes verbatim.
    promote_to_track(destination: 'production', upload_changelogs: true)
  end
```

- [ ] **Step 3: Verify the Fastfile parses and the lanes are registered**

```bash
ruby -c fastlane/Fastfile
bundle exec fastlane lanes
```

Expected: `Syntax OK`, and the lane list contains `distribute_dev`, `distribute_production`,
`promote_beta`, `promote_production` and `build_release_candidates`.

- [ ] **Step 4: Verify the upload guard now rejects beta**

```bash
bundle exec fastlane run_lane_that_does_not_exist 2>/dev/null || true
ruby -r./fastlane/lib/thor_release -e '
begin
  ThorRelease.validate_upload_track!("beta")
  puts "FAIL: beta was accepted as an upload track"
  exit 1
rescue ThorRelease::Error => e
  puts "ok: beta rejected - #{e.message[0, 60]}..."
end'
```

Expected: `ok: beta rejected - upload track must be one of internal, alpha...`

- [ ] **Step 5: Confirm `distribute_production` is now unreachable and remove it**

`distribute_production` at `fastlane/Fastfile:147-150` uploads to `beta`, which
`validate_upload_track!` now rejects. It is superseded by `promote_beta`. Delete the lane and its
`desc` line.

Re-run `bundle exec fastlane lanes` and confirm `distribute_production` is gone and the other four
remain.

- [ ] **Step 6: Run the full test suite**

```bash
.github/scripts/test/run-tests.sh
```

Expected: PASS across every test file.

- [ ] **Step 7: Commit**

```bash
git add fastlane/Fastfile
git commit -m "feat(fastlane): add promote_beta and promote_production lanes

Both build this rung's APKs from this rung's commit, assert the version code
is already in the track below, then promote. Neither uploads an artifact.

Two things that would each have broken this silently:

upload_to_play_store back-fills params[:aab] from
SharedValues::GRADLE_AAB_OUTPUT_PATH whenever it is nil, and both
skip_upload_apk and skip_upload_aab default to false. Since every rung now
builds before promoting, lane_context is populated - so a promote lane
missing either flag uploads the bundle and dies on the duplicate code, which
is the error this whole change removes. Both are set.

promote_track copies the source release object wholesale, so without an
explicit metadata pass production would inherit the dev auto-notes verbatim.
promote_production sets skip_upload_changelogs: false and writes the curated
notes for every locale that has metadata.

Presence is asked as a yes/no assertion, never as 'what is newest'.
google_play_track_version_codes has no status filter and no ordering, and
promotion leaves the code active in the source track - so after the first
ladder run, 'newest in beta' is a dev code.

The upload guard gets stricter, not looser: beta was allowed while promotion
was a manual Console step, and is now promotion-only. distribute_production
uploaded to beta and is therefore both unreachable and superseded; removed."
```

---

### Task 8: `detect-version-bump.sh`

**Files:**
- Create: `.github/scripts/detect-version-bump.sh`
- Create: `.github/scripts/test/test-detect-version-bump.sh`

**Interfaces:**
- Consumes: nothing.
- Produces, consumed by Task 10's reusable workflow:
  `.github/scripts/detect-version-bump.sh <old-ref> [properties-path]` writes three lines to stdout
  in `key=value` form — `changed=true|false`, `code=<integer>`, `name=<x.y.z>` — suitable for
  appending to `$GITHUB_OUTPUT`. Exit 0 on success, exit 1 when the current code cannot be read.

**Why extract it:** this is the logic currently inlined at `dev-check.yml:110-162`, minus the digit
gate. In a `run:` block it cannot be tested; as a script it can. It **fails open** — if the old value
is unreadable it reports `changed=true` and lets Play adjudicate, rather than silently skipping a
real release.

- [ ] **Step 1: Write the failing test**

Create `.github/scripts/test/test-detect-version-bump.sh`:

```bash
#!/usr/bin/env bash
# Builds a throwaway git repo per case so the assertions are against real
# `git show` behaviour rather than a mock.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/detect-version-bump.sh"
assertions=0
failed=0

setup_repo() {
  d="$(mktemp -d)"
  git -C "$d" init -q
  git -C "$d" config user.email t@example.com
  git -C "$d" config user.name t
  printf 'org.gradle.jvmargs=-Xmx4g\nversionCode=%s\n' "$1" > "$d/gradle.properties"
  git -C "$d" add gradle.properties
  git -C "$d" commit -q -m first
  echo "$d"
}

bump_repo() {
  printf 'org.gradle.jvmargs=-Xmx4g\nversionCode=%s\n' "$2" > "$1/gradle.properties"
  git -C "$1" add gradle.properties
  git -C "$1" commit -q -m bump
}

expect() {
  local label="$1" haystack="$2" needle="$3"
  assertions=$((assertions + 1))
  if printf '%s' "$haystack" | grep -qx -- "$needle"; then
    echo "  ok: $label"
  else
    echo "  FAIL: $label - expected line '$needle' in:"
    printf '%s\n' "$haystack" | sed 's/^/       /'
    failed=1
  fi
}

# 1. An unchanged code is not a release.
d="$(setup_repo 1940)"; git -C "$d" commit -q --allow-empty -m noop
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "unchanged code -> changed=false" "$out" "changed=false"
expect "unchanged code still reports the code" "$out" "code=1940"
rm -rf "$d"

# 2. A bumped code is a release, and the derived name is right.
d="$(setup_repo 1940)"; bump_repo "$d" 1941
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "bumped code -> changed=true" "$out" "changed=true"
expect "bumped code reports the new code" "$out" "code=1941"
expect "bumped code derives the name" "$out" "name=1.94.1"
rm -rf "$d"

# 3. A stable (code ending in 0) is NOT special. The digit gate is gone -
#    branch identity decides the rung now, not arithmetic on the version.
d="$(setup_repo 1939)"; bump_repo "$d" 1940
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "a stable is an ordinary bump" "$out" "changed=true"
expect "a stable derives x.y.0" "$out" "name=1.94.0"
rm -rf "$d"

# 4. Fails OPEN: an unreadable old value must not silently skip a release.
d="$(setup_repo 1940)"
out="$(cd "$d" && bash "$script" 'refs/heads/nonexistent')"
expect "unreadable old ref -> changed=true" "$out" "changed=true"
rm -rf "$d"

# 5. A JVM tweak with no version change is not a release, even though
#    gradle.properties itself changed.
d="$(setup_repo 1940)"
printf 'org.gradle.jvmargs=-Xmx8g\nversionCode=1940\n' > "$d/gradle.properties"
git -C "$d" add gradle.properties; git -C "$d" commit -q -m tune
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "jvmargs change alone -> changed=false" "$out" "changed=false"
rm -rf "$d"

# 6. An unreadable CURRENT value is a hard error, not a fail-open.
d="$(setup_repo 1940)"
printf 'org.gradle.jvmargs=-Xmx4g\n' > "$d/gradle.properties"
assertions=$((assertions + 1))
if (cd "$d" && bash "$script" 'HEAD') >/dev/null 2>&1; then
  echo "  FAIL: missing current versionCode should exit non-zero"
  failed=1
else
  echo "  ok: missing current versionCode exits non-zero"
fi
rm -rf "$d"

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/test-detect-version-bump.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL — every case errors because `detect-version-bump.sh` does not exist yet.

- [ ] **Step 3: Write the script**

Create `.github/scripts/detect-version-bump.sh`:

```bash
#!/usr/bin/env bash
# Did this push change versionCode?
#
# usage: detect-version-bump.sh <old-ref> [properties-path]
# prints: changed=true|false
#         code=<integer>
#         name=<x.y.z>
#
# Compares the PARSED value, not `git diff --quiet -- gradle.properties`: that
# file also carries Gradle daemon flags and memory settings, and a JVM tweak is
# not a release. HEAD^ on a PR merge commit is the previous branch tip (first
# parent), which is the comparison we want.
#
# Fails OPEN on an unreadable OLD value - report a release and let Play
# adjudicate, rather than silently skipping a real one. An unreadable CURRENT
# value is a hard error: there is nothing to publish and nothing to compare.
#
# Play's duplicate-code rejection is not suppressed anywhere and must not be.
# It is the real versioning gate and it is server-side. This script only
# decides whether a push was MEANT to publish.
set -euo pipefail

old_ref="${1:?usage: detect-version-bump.sh <old-ref> [properties-path]}"
props="${2:-gradle.properties}"

# Anchored, and rejects comments: an unanchored match also finds
# initialVersionCode=1921, which fed two lines into arithmetic and made the old
# release-manager workflow unusable.
read_code() {
  grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' \
    | head -n 1 | cut -d= -f2 | tr -d '[:space:]'
}

new_code="$(read_code < "$props" || true)"
if [ -z "$new_code" ]; then
  echo "::error::versionCode not found in $props" >&2
  exit 1
fi

old_code="$(git show "${old_ref}:${props}" 2>/dev/null | read_code || true)"

name="$((new_code / 1000)).$(((new_code % 1000) / 10)).$((new_code % 10))"

if [ -n "$old_code" ] && [ "$old_code" = "$new_code" ]; then
  echo "::notice::versionCode unchanged at $new_code - building for verification only." >&2
  echo "changed=false"
else
  echo "::notice::versionCode ${old_code:-<unreadable>} -> $new_code - publishing." >&2
  echo "changed=true"
fi

echo "code=$new_code"
echo "name=$name"
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
chmod +x .github/scripts/detect-version-bump.sh
.github/scripts/test/run-tests.sh
```

Expected: PASS — 11 assertions in `test-detect-version-bump.sh`, all `ok:`.

- [ ] **Step 5: Check it with shellcheck**

```bash
shellcheck -x -S style .github/scripts/detect-version-bump.sh \
                       .github/scripts/test/test-detect-version-bump.sh
```

Expected: clean. If SC2094 or similar fires on the `read_code` pipeline, fix the script rather than
suppressing — this script decides whether a release happens.

- [ ] **Step 6: Commit**

```bash
git add .github/scripts/detect-version-bump.sh \
        .github/scripts/test/test-detect-version-bump.sh
git commit -m "feat(ci): extract version-bump detection into a tested script

This is dev-check.yml:110-162 minus the digit gate. In a run: block it could
not be tested; as a script it has eleven assertions against a real throwaway
git repo, including the two behaviours that are easy to get backwards:

- it fails OPEN on an unreadable old value (report a release, let Play
  adjudicate) but hard-fails on an unreadable current one;
- a JVM tweak to gradle.properties is not a release, because it compares the
  parsed value rather than diffing the file.

The versionCode-ends-in-0 gate is deliberately absent. Branch identity
decides the rung now, so a stable is an ordinary bump."
```

---

### Task 9: `check-notes-budget.sh` — the pre-flight size gate

**Files:**
- Create: `.github/scripts/check-notes-budget.sh`
- Create: `.github/scripts/test/test-check-notes-budget.sh`

**Interfaces:**
- Consumes: nothing.
- Produces, consumed by Task 10: `.github/scripts/check-notes-budget.sh <version-name> [wrapper-units]`
  — exit 0 if the notes fit, exit 1 with a per-file report if not. `wrapper-units` defaults to `160`.

**Why:** Telegram caps a `sendDocument` **caption** at 1024 UTF-16 units and **rejects** an oversized
one — it does not truncate. The `curl` has no `--fail` and its output is discarded, so the step goes
green having posted nothing. `playstore.txt` is capped at 500 characters. CI checks neither today.

The gate must run **pre-flight**, before anything publishes. In the current `dev-check.yml` the
Telegram step runs after the Play upload and before the GitHub release, so a guard bolted on there
fails a half-published release.

Measured wrapper overhead: **149** units on the `dev-check.yml` path and **141** on
`telegram-release.yml`. The default of 160 leaves headroom; the caller passes an exact value.

- [ ] **Step 1: Write the failing test**

Create `.github/scripts/test/test-check-notes-budget.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/check-notes-budget.sh"
assertions=0
failed=0

make_notes() {
  # $1 = dir, $2 = telegram byte count, $3 = playstore byte count
  mkdir -p "$1/release-notes/v9.99.9"
  head -c "$2" /dev/zero | tr '\0' 'a' > "$1/release-notes/v9.99.9/telegram.md"
  head -c "$3" /dev/zero | tr '\0' 'b' > "$1/release-notes/v9.99.9/playstore.txt"
}

check() {
  local label="$1" expect_pass="$2" dir="$3" wrapper="${4:-160}"
  assertions=$((assertions + 1))
  if (cd "$dir" && bash "$script" 9.99.9 "$wrapper") >/dev/null 2>&1; then
    got=pass
  else
    got=fail
  fi
  if [ "$got" = "$expect_pass" ]; then
    echo "  ok: $label ($got)"
  else
    echo "  FAIL: $label - expected $expect_pass, got $got"
    failed=1
  fi
}

# Comfortably inside both budgets.
d="$(mktemp -d)"; make_notes "$d" 600 300
check "notes within budget" pass "$d"
rm -rf "$d"

# 900 + 160 wrapper = 1060 > 1024.
d="$(mktemp -d)"; make_notes "$d" 900 300
check "telegram over the caption cap" fail "$d"
rm -rf "$d"

# Exactly at the cap: 864 + 160 = 1024. Must pass - the limit is inclusive.
d="$(mktemp -d)"; make_notes "$d" 864 300
check "telegram exactly at the cap" pass "$d"
rm -rf "$d"

# One over.
d="$(mktemp -d)"; make_notes "$d" 865 300
check "telegram one unit over" fail "$d"
rm -rf "$d"

# playstore.txt cap is 500.
d="$(mktemp -d)"; make_notes "$d" 600 520
check "playstore over 500 chars" fail "$d"
rm -rf "$d"

# An emoji is ONE character but TWO UTF-16 units. Counting bytes or
# characters instead of UTF-16 units is the whole reason this gate exists.
d="$(mktemp -d)"
mkdir -p "$d/release-notes/v9.99.9"
python3 -c "
import sys
# 500 rockets = 1000 UTF-16 units. Plus a 30-unit wrapper = 1030 > 1024.
open(sys.argv[1], 'w', encoding='utf-8').write('\U0001F680' * 500)
open(sys.argv[2], 'w', encoding='utf-8').write('ok')
" "$d/release-notes/v9.99.9/telegram.md" "$d/release-notes/v9.99.9/playstore.txt"
check "emoji counted as 2 UTF-16 units" fail "$d" 30
rm -rf "$d"

# Missing notes must not pass silently.
d="$(mktemp -d)"; mkdir -p "$d/release-notes"
check "missing notes dir" fail "$d"
rm -rf "$d"

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/test-check-notes-budget.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL — the script does not exist, so every case reports `fail` and the three
`expect_pass=pass` cases mismatch.

- [ ] **Step 3: Write the script**

Create `.github/scripts/check-notes-budget.sh`:

```bash
#!/usr/bin/env bash
# Pre-flight size gate for release notes.
#
# usage: check-notes-budget.sh <version-name> [wrapper-units]
#
# Telegram caps a sendDocument CAPTION at 1024 UTF-16 units and REJECTS an
# oversized one - it does not truncate. The send has no --fail and its output
# is discarded, so an over-budget caption makes the step go green having posted
# nothing. That is why this runs pre-flight, before anything publishes: bolting
# it next to the curl would fail a half-published release.
#
# Measure UTF-16 units, not bytes and not characters. One emoji is a single
# character, two UTF-16 units, and four UTF-8 bytes.
#
# wrapper-units is the size of the caption scaffolding the workflow wraps
# around telegram.md - measured at 149 on the dev path and 141 on
# telegram-release.yml. Default 160 leaves headroom.
set -euo pipefail

version_name="${1:?usage: check-notes-budget.sh <version-name> [wrapper-units]}"
wrapper_units="${2:-160}"

TELEGRAM_CAP=1024
PLAYSTORE_CAP=500

dir="release-notes/v${version_name}"
[ -d "$dir" ] || dir="release-notes/${version_name}"
if [ ! -d "$dir" ]; then
  echo "::error::no release-notes directory for v${version_name}" >&2
  exit 1
fi

status=0

telegram="$dir/telegram.md"
if [ -f "$telegram" ]; then
  units="$(python3 -c "
import sys
text = open(sys.argv[1], encoding='utf-8').read()
print(len(text.encode('utf-16-le')) // 2)
" "$telegram")"
  total=$((units + wrapper_units))
  if [ "$total" -gt "$TELEGRAM_CAP" ]; then
    echo "::error::telegram caption is ${total} UTF-16 units (${units} in file + ${wrapper_units} wrapper), cap is ${TELEGRAM_CAP}. Telegram rejects rather than truncates, and the send reports success either way." >&2
    status=1
  else
    echo "  telegram: ${total}/${TELEGRAM_CAP} units ($((TELEGRAM_CAP - total)) spare)"
  fi
else
  echo "::warning::no telegram.md in $dir" >&2
fi

playstore="$dir/playstore.txt"
if [ -f "$playstore" ]; then
  chars="$(python3 -c "
import sys
print(len(open(sys.argv[1], encoding='utf-8').read()))
" "$playstore")"
  if [ "$chars" -gt "$PLAYSTORE_CAP" ]; then
    echo "::error::playstore.txt is ${chars} characters, cap is ${PLAYSTORE_CAP}." >&2
    status=1
  else
    echo "  playstore: ${chars}/${PLAYSTORE_CAP} characters"
  fi
else
  echo "::error::no playstore.txt in $dir" >&2
  status=1
fi

exit "$status"
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
chmod +x .github/scripts/check-notes-budget.sh
.github/scripts/test/run-tests.sh
```

Expected: PASS — 7 assertions, all `ok:`, including the emoji case.

- [ ] **Step 5: Run it against the real v1.94.0 notes**

```bash
.github/scripts/check-notes-budget.sh 1.94.0 149
```

Expected: two lines reporting usage against each cap, exit 0. Record the reported spare in the
commit message — v1.93.2 shipped at 977/1024 and a one-line accuracy fix in review took it to
1006, 18 units from silence.

- [ ] **Step 6: Commit**

```bash
git add .github/scripts/check-notes-budget.sh \
        .github/scripts/test/test-check-notes-budget.sh
git commit -m "feat(ci): add a pre-flight release-notes size gate

Telegram caps a sendDocument caption at 1024 UTF-16 units and REJECTS an
oversized one rather than truncating. The send has no --fail and discards its
output, so an over-budget caption makes the step go green having posted
nothing. v1.93.2 shipped at 977/1024 and a one-line accuracy fix in review
took it to 1006 - eighteen units from silence, caught only by a hand-run
python one-liner.

Counted in UTF-16 units, not bytes or characters: one emoji is one
character, two UTF-16 units and four UTF-8 bytes. There is a test for
exactly that.

Runs pre-flight rather than next to the curl. In the current workflow the
Telegram step sits between the Play upload and the GitHub release, so a guard
bolted on there would fail a half-published release.

Also caps playstore.txt at 500 characters, which CI has never checked.

Closes docs/follow-ups/telegram-caption-length-guard.md."
```

---

### Task 10: `release-rung.yml` — one reusable workflow, three rungs

**Files:**
- Create: `.github/workflows/release-rung.yml`

**Interfaces:**
- Consumes: `.github/scripts/detect-version-bump.sh` and `.github/scripts/check-notes-budget.sh`
  (Tasks 8 and 9); the Fastfile lanes `distribute_dev`, `promote_beta`, `promote_production` and
  `build_release_candidates` (Task 7).
- Produces, consumed by Task 11's three callers — a `workflow_call` contract:

  | input | type | required | meaning |
  |---|---|---|---|
  | `rung` | string | yes | `dev` \| `beta` \| `production`. Labels and log lines only. |
  | `fastlane_lane` | string | yes | Lane to run when publishing. |
  | `tag_suffix` | string | yes | Appended to `v<version-name>`. `''` for production. |
  | `title_prefix` | string | yes | GitHub release title prefix. |
  | `prerelease` | boolean | yes | GitHub release `prerelease` flag. |
  | `telegram` | boolean | yes | Whether to broadcast the APK. |
  | `require_notes` | boolean | yes | Hard-fail when no curated notes exist. |
  | `on_unchanged_version` | string | yes | `skip` \| `fail`. What an unbumped code means for this rung. |
  | `caption_wrapper_units` | number | no (`149`) | Caption scaffolding size, for the budget gate. |

  Outputs: `published` (`'true'`/`'false'`), `version_name`, `version_code`.
  Secrets are taken with `secrets: inherit` from the caller.

**Why one file:** the three rungs differ in nine declared values and nothing else. Today the same
~250 lines exist twice, in `dev-check.yml` and `production-deploy.yml`, and they have already
drifted — only one has a Telegram step, only one has a version-code guard, and neither sets
`target_commitish`. A shared workflow makes "the rungs behave identically except where declared"
structural instead of aspirational.

- [ ] **Step 1: Create the reusable workflow**

Create `.github/workflows/release-rung.yml`:

```yaml
name: Release rung

# One workflow, three rungs. dev uploads to Play alpha; master promotes
# alpha -> beta; production promotes beta -> production. Exactly one branch
# ever uploads an artifact, which is what makes Play's per-app (not per-track)
# version-code uniqueness a non-issue rather than an error to handle.
#
# The rungs differ only in the inputs declared below. If a behaviour needs to
# differ between them, add an input - do not fork this file.
on:
  workflow_call:
    inputs:
      rung:
        description: 'dev | beta | production. Labels and log lines only.'
        required: true
        type: string
      fastlane_lane:
        description: 'Lane to run when publishing.'
        required: true
        type: string
      tag_suffix:
        description: "Appended to v<version-name>. Empty for production."
        required: true
        type: string
      title_prefix:
        description: 'GitHub release title prefix.'
        required: true
        type: string
      prerelease:
        required: true
        type: boolean
      telegram:
        required: true
        type: boolean
      require_notes:
        description: 'Hard-fail when no curated release notes exist.'
        required: true
        type: boolean
      on_unchanged_version:
        description: "skip | fail. What an unbumped versionCode means here."
        required: true
        type: string
      caption_wrapper_units:
        description: 'UTF-16 units of caption scaffolding, for the budget gate.'
        required: false
        default: 149
        type: number
    outputs:
      published:
        value: ${{ jobs.rung.outputs.published }}
      version_name:
        value: ${{ jobs.rung.outputs.version_name }}
      version_code:
        value: ${{ jobs.rung.outputs.version_code }}

jobs:
  rung:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    outputs:
      published: ${{ steps.gate.outputs.publish }}
      version_name: ${{ steps.ver.outputs.name }}
      version_code: ${{ steps.ver.outputs.code }}

    steps:
      - name: Checkout code
        uses: actions/checkout@v7
        with:
          fetch-depth: 0 # full history + tags so the release-notes fallback works

      - name: Detect version bump
        id: ver
        env:
          BEFORE: ${{ github.event.before }}
        run: .github/scripts/detect-version-bump.sh "${BEFORE:-HEAD^}" >> "$GITHUB_OUTPUT"

      # A rung decides for itself what an unbumped code means. dev and master
      # treat it as "nothing to publish, build for verification only".
      # production treats it as an error: there is nothing to promote, and Play
      # would otherwise reject the run seven minutes later.
      #
      # workflow_dispatch has no github.event.before, so detect-version-bump
      # fails open and reports changed=true. That is the deliberate escape
      # hatch for re-publishing a code Play never accepted.
      - name: Decide whether this run publishes
        id: gate
        env:
          CHANGED: ${{ steps.ver.outputs.changed }}
          ON_UNCHANGED: ${{ inputs.on_unchanged_version }}
          RUNG: ${{ inputs.rung }}
          CODE: ${{ steps.ver.outputs.code }}
        run: |
          if [ "$CHANGED" = "true" ]; then
            echo "publish=true" >> "$GITHUB_OUTPUT"
            exit 0
          fi

          if [ "$ON_UNCHANGED" = "fail" ]; then
            echo "::error::versionCode is still ${CODE}, which Google Play has already taken — there is nothing for the ${RUNG} rung to promote. Land a chore(release) bump first, or re-run via workflow_dispatch if you know Play never accepted ${CODE}." >&2
            exit 1
          fi

          echo "::notice::versionCode unchanged at ${CODE} — building for verification only, not publishing."
          echo "publish=false" >> "$GITHUB_OUTPUT"

      # Pre-flight, before anything publishes. Bolting this next to the
      # Telegram send would fail a half-published release, because that step
      # sits between the Play action and the GitHub release.
      - name: Check release-notes budget
        if: steps.gate.outputs.publish == 'true'
        env:
          NAME: ${{ steps.ver.outputs.name }}
          REQUIRE: ${{ inputs.require_notes }}
          WRAPPER: ${{ inputs.caption_wrapper_units }}
        run: |
          if [ ! -d "release-notes/v${NAME}" ] && [ ! -d "release-notes/${NAME}" ]; then
            if [ "$REQUIRE" = "true" ]; then
              echo "::error::v${NAME} publishes to users and has no release-notes directory. Curated notes are required on this rung." >&2
              exit 1
            fi
            echo "::notice::No curated notes for v${NAME} — the GitHub body will fall back to the commit log."
            exit 0
          fi
          .github/scripts/check-notes-budget.sh "$NAME" "$WRAPPER"

      - name: Setup JDK 21
        uses: actions/setup-java@v5.6.0
        with:
          distribution: 'zulu'
          java-version: '21'
          cache: 'gradle'

      - name: Setup Ruby
        uses: ruby/setup-ruby@95ef2b042f9d7a56d8268cba8559e2842e2ad01b # v1
        with:
          ruby-version: '3.3'
          bundler-cache: true

      # --- SECRET DECODING ---
      - name: Decode Keystore
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: echo "$ANDROID_KEYSTORE_BASE64" | base64 --decode > app/release.jks

      - name: Decode Google Play Service Account
        env:
          PLAY_STORE_JSON_KEY: ${{ secrets.PLAY_STORE_JSON_KEY }}
        run: echo "$PLAY_STORE_JSON_KEY" > app/google-play-api.json

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      # When this run does not publish it still builds, because a green build
      # on the merged tree is the point of the run. build_release_candidates
      # touches Play not at all.
      - name: Run Fastlane
        env:
          JSON_KEY_FILE: ${{ github.workspace }}/app/google-play-api.json
          SUPPLY_JSON_KEY: ${{ github.workspace }}/app/google-play-api.json
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEYSTORE_FILE_PATH: ${{ github.workspace }}/app/release.jks
        run: |
          bundle exec fastlane android ${{ steps.gate.outputs.publish == 'true' && inputs.fastlane_lane || 'build_release_candidates' }}

      - name: Prepare Release Notes
        id: prep_notes
        if: steps.gate.outputs.publish == 'true'
        run: |
          if [ ! -s version_name.txt ]; then
            echo "::error::version_name.txt is missing or empty — the Fastlane lane did not write it." >&2
            exit 1
          fi
          VERSION_NAME=$(cat version_name.txt)
          echo "version_name=$VERSION_NAME" >> "$GITHUB_OUTPUT"

          resolve() {
            # release-notes/v<name>/ is the convention; the un-prefixed form is
            # tolerated because older releases used it.
            if [ -f "release-notes/v${VERSION_NAME}/$1" ]; then
              echo "release-notes/v${VERSION_NAME}/$1"
            elif [ -f "release-notes/${VERSION_NAME}/$1" ]; then
              echo "release-notes/${VERSION_NAME}/$1"
            fi
          }

          GITHUB_NOTES_FILE="$(resolve github.md)"
          if [ -z "$GITHUB_NOTES_FILE" ]; then
            FALLBACK_FILE="github_release_notes_fallback.md"
            {
              echo "### Changelog"
              echo ""
            } > "$FALLBACK_FILE"
            LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
            if [ -n "$LAST_TAG" ]; then
              echo "Commits since $LAST_TAG:" >> "$FALLBACK_FILE"
              git log "$LAST_TAG..HEAD" --oneline >> "$FALLBACK_FILE"
            else
              echo "Recent commits:" >> "$FALLBACK_FILE"
              git log --oneline -n 20 >> "$FALLBACK_FILE"
            fi
            GITHUB_NOTES_FILE="$FALLBACK_FILE"
          fi
          echo "github_notes_path=$GITHUB_NOTES_FILE" >> "$GITHUB_OUTPUT"

          TELEGRAM_NOTES_FILE="$(resolve telegram.md)"
          echo "telegram_notes_path=$TELEGRAM_NOTES_FILE" >> "$GITHUB_OUTPUT"

      - name: Send APK to Telegram
        if: inputs.telegram && steps.gate.outputs.publish == 'true'
        env:
          TELEGRAM_TOKEN: ${{ secrets.TELEGRAM_TOKEN }}
          TELEGRAM_CHAT_ID: ${{ secrets.TELEGRAM_CHAT_ID }}
          VERSION_NAME: ${{ steps.prep_notes.outputs.version_name }}
          NOTES_PATH: ${{ steps.prep_notes.outputs.telegram_notes_path }}
        run: |
          # PORTING NOTE: copy the body of "Send APK to Telegram" from the
          # pre-change .github/workflows/dev-check.yml (lines 236-285 at
          # ee9a6871) verbatim, then apply exactly these three changes:
          #
          #   1. Replace the inline notes-path resolution with $NOTES_PATH,
          #      which prep_notes already resolved above.
          #   2. Add --fail-with-body to the curl. Telegram answers a rejected
          #      caption with HTTP 400 and a JSON reason; without this flag the
          #      step is green either way. The size gate above removes the
          #      common cause, not every cause.
          #   3. Leave the TRACK_LABEL case and the printf caption assembly
          #      untouched. track.txt is written by every lane, including the
          #      promote lanes, which set it to the destination track.
          #
          # Do not retype the caption from memory — the assembled caption is
          # measured against a 1024-unit budget and the wrapper size is a
          # calibrated constant.
          echo "see porting note" && exit 1

      # target_commitish pins the tag to the commit that was actually built.
      # Without it the tag is created against the branch's tip AT THE MOMENT
      # THE API CALL LANDS, so a push arriving during the ~7 minute build moves
      # the tag off the built tree. IzzyOnDroid's rbtlog clones at the tag and
      # asserts `git rev-parse HEAD` equals the APK's embedded commit, so a
      # drifted tag is a failed reproducibility check, not a cosmetic problem.
      #
      # Existing stable tags are correct only because production has never
      # carried a commit of its own. That is a property of the history so far,
      # not a guarantee.
      - name: Create GitHub Release
        if: steps.gate.outputs.publish == 'true'
        uses: softprops/action-gh-release@3d0d9888cb7fd7b750713d6e236d1fcb99157228 # v3.0.2
        with:
          tag_name: v${{ steps.prep_notes.outputs.version_name }}${{ inputs.tag_suffix }}
          target_commitish: ${{ github.sha }}
          name: ${{ inputs.title_prefix }} v${{ steps.prep_notes.outputs.version_name }}
          body_path: ${{ steps.prep_notes.outputs.github_notes_path }}
          files: |
            app/build/distribution/foss/foss-release.apk
            app/build/distribution/store/store-release.apk
          draft: false
          prerelease: ${{ inputs.prerelease }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      # --- SECURITY CLEANUP ---
      - name: Cleanup sensitive files
        if: always()
        run: |
          rm -f app/release.jks
          rm -f app/google-play-api.json
```

- [ ] **Step 2: Port the Telegram step body**

Replace the `echo "see porting note" && exit 1` placeholder with the real body:

```bash
git show ee9a6871:.github/workflows/dev-check.yml | sed -n '236,285p'
```

Copy that `run:` body into the step, then make the three changes listed in the porting note. The
step must end with a `curl` that carries `--fail-with-body`:

```bash
curl -s --fail-with-body \
  -F chat_id="${TELEGRAM_CHAT_ID}" \
  -F document=@"${APK_PATH}" \
  -F caption="${CAPTION}" \
  -F parse_mode=Markdown \
  "https://api.telegram.org/bot${TELEGRAM_TOKEN}/sendDocument" > /dev/null
```

Confirm no placeholder survives:

```bash
grep -n 'see porting note' .github/workflows/release-rung.yml && echo "PLACEHOLDER STILL PRESENT" || echo "clean"
```

Expected: `clean`.

- [ ] **Step 3: Lint the workflow**

```bash
actionlint .github/workflows/release-rung.yml
shellcheck -x -S style <(echo '#!/usr/bin/env bash') # sanity: shellcheck is on PATH
```

Expected: actionlint clean. If actionlint is not installed locally, the `static-analysis` job from
Task 1 will run it on the PR — but install it locally first; a round-trip through CI for a typo in a
`workflow_call` contract is a slow way to find out.

- [ ] **Step 4: Verify the contract shape mechanically**

```bash
python3 - <<'PY'
import yaml
wf = yaml.safe_load(open('.github/workflows/release-rung.yml'))
call = wf[True]['workflow_call'] if True in wf else wf['on']['workflow_call']
required = {k for k, v in call['inputs'].items() if v.get('required')}
expected = {'rung', 'fastlane_lane', 'tag_suffix', 'title_prefix',
            'prerelease', 'telegram', 'require_notes', 'on_unchanged_version'}
assert required == expected, f"required inputs drifted: {required ^ expected}"
assert set(call['outputs']) == {'published', 'version_name', 'version_code'}
# The AAB must not be a release asset on any rung.
job = wf['jobs']['rung']
rel = next(s for s in job['steps'] if s.get('name') == 'Create GitHub Release')
assert '.aab' not in rel['with']['files'], "AAB is still a release asset"
assert rel['with']['target_commitish'] == '${{ github.sha }}'
print("ok: workflow_call contract and release step verified")
PY
```

Expected: `ok: workflow_call contract and release step verified`.

Note: PyYAML parses the bare `on:` key as Python `True`, which is why the lookup handles both — the
same quirk Task 2's test works around.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release-rung.yml
git commit -m "feat(ci): add release-rung.yml, one reusable workflow for all three rungs

dev-check.yml and production-deploy.yml carried ~250 near-identical lines
each and had already drifted: only one has a Telegram step, only one has a
version-code guard, and neither sets target_commitish. The rungs genuinely
differ in nine values, so those nine are inputs and everything else is
shared. If a behaviour needs to differ, add an input rather than forking the
file.

Three behaviours worth naming:

target_commitish pins the tag to the commit that was built. Without it the
tag lands on the branch tip at the moment the API call arrives, so a push
during the ~7 minute build moves the tag off the built tree - and
IzzyOnDroid's rbtlog clones at the tag and asserts the checkout matches the
commit the APK embeds. Today's stable tags are correct only because
production has never carried a commit of its own, which is a property of the
history rather than a guarantee.

on_unchanged_version makes each rung state what an unbumped code means:
dev and master build for verification, production fails, because there is
nothing to promote and Play would reject it seven minutes later anyway.

The notes budget gate runs pre-flight. Next to the Telegram send it would
fail a half-published release."
```

---

### Task 11: The three callers, and deleting the two old workflows

**Files:**
- Create: `.github/workflows/1-dev-publish.yml`
- Create: `.github/workflows/2-master-promote.yml`
- Create: `.github/workflows/3-production-promote.yml`
- Delete: `.github/workflows/dev-check.yml`
- Delete: `.github/workflows/production-deploy.yml`
- Modify: `.github/workflows/pr-ci.yml` — add `production` to the `pull_request` branch list

**Interfaces:**
- Consumes: the `workflow_call` contract from Task 10.
- Produces: nothing consumed by later tasks. This is the switchover.

**Order matters.** Create the three callers and delete the two old workflows **in the same commit**.
A window where both exist means two workflows racing for one Play version code — the exact failure
this plan removes.

**The trigger change that makes the digit gate unnecessary.** `dev-check.yml` fires on pushes to
`master`; the new `1-dev-publish.yml` fires on pushes to **`dev`**. That single change is what
retires the ends-in-`0` heuristic: a rung is identified by the branch it runs on, not by arithmetic
on the version it happens to carry.

- [ ] **Step 1: Create the dev caller**

Create `.github/workflows/1-dev-publish.yml`:

```yaml
name: 1. Dev — publish to Play alpha

# Rung 1 of 3. This is the ONLY workflow in the repo that uploads an artifact
# to Play. master and production promote what this uploaded; they never upload.
# That is what gives every version code exactly one uploader.
on:
  push:
    branches: [ "dev" ]
    # A change that cannot reach the APK must not spend seven minutes and a
    # Play upload proving it. Safe to filter the whole workflow because this is
    # not a required status check.
    #
    # gradle.properties and release-notes/** stay OUT of this list: a release
    # push carries the version bump, so it is never skipped.
    paths-ignore:
      - '*.md'
      - 'docs/**'
      - 'web/**'
      - 'vercel.json'
      - '.github/workflows/web-*.yml'
      - '.github/labeler.yml'
      - 'LICENSE'
      - '.gitignore'
      - '.github/**/*.md'
      - '.github/ISSUE_TEMPLATE/**'
      - '.github/assets/**'
      - '.github/CODEOWNERS'
      - '.github/FUNDING.yml'
      - '.github/dependabot.yml'
  workflow_dispatch:

concurrency:
  group: release-dev
  cancel-in-progress: false

jobs:
  publish:
    uses: ./.github/workflows/release-rung.yml
    permissions:
      contents: write
    secrets: inherit
    with:
      rung: dev
      fastlane_lane: distribute_dev
      # github.run_number, not the version code: re-running a failed publish on
      # the same commit mints a second tag rather than colliding. The real
      # duplicate guard is Play's server-side check, which is not suppressed.
      tag_suffix: -dev-${{ github.run_number }}
      title_prefix: Dev Build
      prerelease: true
      telegram: true
      # Notes are optional here. A dev build falls back to the commit log; the
      # curated notes are written once, for the stable.
      require_notes: false
      on_unchanged_version: skip
```

Note that `.github/scripts/**` is deliberately **absent** from `paths-ignore`: the rung now depends
on those scripts, so a change to one must be exercised.

- [ ] **Step 2: Create the master caller**

Create `.github/workflows/2-master-promote.yml`:

```yaml
name: 2. Master — promote alpha to beta

# Rung 2 of 3. Promotes the build dev already uploaded from Play's alpha
# (Closed testing) to beta (Open testing). Uploads nothing.
on:
  push:
    branches: [ "master" ]
    paths-ignore:
      - '*.md'
      - 'docs/**'
      - 'web/**'
      - 'vercel.json'
      - '.github/workflows/web-*.yml'
      - '.github/labeler.yml'
      - 'LICENSE'
      - '.gitignore'
      - '.github/**/*.md'
      - '.github/ISSUE_TEMPLATE/**'
      - '.github/assets/**'
      - '.github/CODEOWNERS'
      - '.github/FUNDING.yml'
      - '.github/dependabot.yml'
  workflow_dispatch:

concurrency:
  group: release-beta
  cancel-in-progress: false

jobs:
  promote:
    uses: ./.github/workflows/release-rung.yml
    permissions:
      contents: write
    secrets: inherit
    with:
      rung: beta
      fastlane_lane: promote_beta
      tag_suffix: -beta-${{ github.run_number }}
      title_prefix: Beta
      prerelease: true
      # Open testers already have the build from Play. The Telegram broadcast
      # is the dev channel's, and the production announcement is the owner's.
      telegram: false
      require_notes: false
      on_unchanged_version: skip
```

- [ ] **Step 3: Create the production caller**

Create `.github/workflows/3-production-promote.yml`:

```yaml
name: 3. Production — promote beta to production

# Rung 3 of 3. Promotes the build master already moved into beta out to
# production, and mints the only GitHub release that is not a pre-release.
# Uploads nothing.
on:
  push:
    branches: [ "production" ]
    paths-ignore:
      - '*.md'
      - 'docs/**'
      - 'web/**'
      - 'vercel.json'
      - '.github/workflows/web-*.yml'
      - '.github/labeler.yml'
      - 'LICENSE'
      - '.gitignore'
      - '.github/**/*.md'
      - '.github/ISSUE_TEMPLATE/**'
      - '.github/assets/**'
      - '.github/CODEOWNERS'
      - '.github/FUNDING.yml'
      - '.github/dependabot.yml'
  workflow_dispatch:

concurrency:
  group: release-production
  cancel-in-progress: false

jobs:
  promote:
    uses: ./.github/workflows/release-rung.yml
    permissions:
      contents: write
    secrets: inherit
    with:
      rung: production
      fastlane_lane: promote_production
      # No suffix: this is the tag Obtainium, IzzyOnDroid and the Shizu store's
      # /releases/latest/ URL all resolve to.
      tag_suffix: ''
      title_prefix: Release
      prerelease: false
      telegram: true
      # A release that reaches users gets notes a human wrote. There is no
      # commit-log fallback on this rung.
      require_notes: true
      # Nothing to promote, and Play would reject it after the full build.
      on_unchanged_version: fail
```

- [ ] **Step 4: Delete the two superseded workflows**

```bash
git rm .github/workflows/dev-check.yml .github/workflows/production-deploy.yml
```

- [ ] **Step 5: Add `production` to `pr-ci.yml`'s branch list**

`production` now receives PRs, so its PRs need the same gate the other two get. In
`.github/workflows/pr-ci.yml`, change:

```yaml
on:
  pull_request:
    branches: [ "master", "dev" ]
```

to:

```yaml
on:
  pull_request:
    branches: [ "master", "dev", "production" ]
```

- [ ] **Step 6: Verify the digit gate is gone repo-wide**

```bash
grep -rn 'ends in 0\|ends with 0\|\*0)' .github/workflows/ || echo "clean: no digit gate remains"
```

Expected: `clean: no digit gate remains`.

- [ ] **Step 7: Verify exactly one workflow uploads to Play**

```bash
python3 - <<'PY'
import pathlib, yaml
uploaders = []
for p in sorted(pathlib.Path('.github/workflows').glob('*.yml')):
    text = p.read_text()
    if 'distribute_dev' in text:
        uploaders.append(p.name)
print("uploaders:", uploaders)
assert uploaders == ['1-dev-publish.yml'], f"expected exactly one uploader, got {uploaders}"
# And every caller must reach the shared rung.
for name in ('1-dev-publish.yml', '2-master-promote.yml', '3-production-promote.yml'):
    wf = yaml.safe_load(open(f'.github/workflows/{name}'))
    job = next(iter(wf['jobs'].values()))
    assert job['uses'] == './.github/workflows/release-rung.yml', name
    assert job['secrets'] == 'inherit', name
print("ok: one uploader, three callers, all on the shared rung")
PY
```

Expected: `ok: one uploader, three callers, all on the shared rung`.

Note: `manual-build.yml` and `telegram-release.yml` remain `workflow_dispatch`-only and touch Play
not at all — the assertion above holds with them present.

- [ ] **Step 8: Run the full test suite and actionlint**

```bash
.github/scripts/test/run-tests.sh
actionlint
```

Expected: all tests pass; actionlint clean across every workflow. In particular Task 2's
`test-release-tag-pinning.sh` must still find at least one `softprops/action-gh-release` step and
must still pass — it now finds the one in `release-rung.yml` instead of the two it found before.

- [ ] **Step 9: Commit**

```bash
git add .github/workflows/1-dev-publish.yml \
        .github/workflows/2-master-promote.yml \
        .github/workflows/3-production-promote.yml \
        .github/workflows/dev-check.yml \
        .github/workflows/production-deploy.yml \
        .github/workflows/pr-ci.yml
git commit -m "feat(ci): switch to the three-rung ladder

dev uploads to Play alpha; master promotes alpha to beta; production
promotes beta to production. Each rung is nine declared values on top of
release-rung.yml.

The trigger change is the point: dev-check.yml fired on pushes to master,
and 1-dev-publish.yml fires on pushes to dev. That is what retires the
versionCode-ends-in-0 heuristic. Play allows one upload per code per app, so
two branches fed from dev were racing for the same code and whichever landed
first killed the other; the digit rule kept them apart by arithmetic on the
version name, which is why a stable's release-notes merge still tripped the
dev build. A rung is now identified by the branch it runs on.

Both old workflows are deleted in this same commit. A window where the old
and new files both exist is two workflows racing for one Play version code,
which is the failure being removed.

.github/scripts/** is deliberately absent from every paths-ignore list: the
rungs now depend on those scripts, so a change to one must be exercised.

production receives PRs now, so pr-ci.yml gates it like the other two.

Supersedes docs/follow-ups/two-branches-one-play-version-code.md."
```

---

### Task 12: Rebind the Shizu manifest checker to `production`

**Files:**
- Modify: `.github/scripts/check-shizu-manifest.sh:245-262`
- Modify: `.github/scripts/sync-shizu-changelog.sh` — the matching block, per its LOCKSTEP comment
- Create: `.github/scripts/test/test-shizu-version-source.sh`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

**The bug this fixes.** `shizu_store.json` lives on `master` and its `download_url` points at
`/releases/latest/`, which GitHub resolves to the newest **non-pre-release** — under the new ladder,
the last `production` release. Both scripts derive the expected version by reading `versionCode`
from the **working tree's** `gradle.properties`. On `master` that is now a *beta* code, one or more
ahead of what `/releases/latest/` serves. The two clocks diverged by design under the old routing
too, and `check-shizu-manifest.sh` deliberately made no version claim about the URL — but the
changelog it writes is now a beta's changelog attached to a stable's APK.

Fix: derive the version from `origin/production`, not from the working tree.

`check-shizu-manifest.sh:245-262` carries an explicit comment — *"this block (versionCode grep,
version arithmetic, notes fallback path) is kept in lockstep with sync-shizu-changelog.sh — update
both scripts together."* Honour it.

- [ ] **Step 1: Write the failing test**

Create `.github/scripts/test/test-shizu-version-source.sh`:

```bash
#!/usr/bin/env bash
# The two Shizu scripts must read gradle.properties from the production
# branch, because shizu_store.json's download_url resolves to the newest
# non-pre-release - which under the three-rung ladder is production's build,
# not the working tree's.
set -euo pipefail
scripts_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assertions=0
failed=0

check() {
  local label="$1" file="$2" pattern="$3" want="$4"
  assertions=$((assertions + 1))
  if grep -qE -- "$pattern" "$file"; then got=present; else got=absent; fi
  if [ "$got" = "$want" ]; then
    echo "  ok: $label ($got)"
  else
    echo "  FAIL: $label - expected $want, got $got for /$pattern/ in $file"
    failed=1
  fi
}

for f in check-shizu-manifest.sh sync-shizu-changelog.sh; do
  path="$scripts_dir/$f"
  assertions=$((assertions + 1))
  if [ -f "$path" ]; then
    echo "  ok: $f exists"
  else
    echo "  FAIL: $f is missing"
    failed=1
    continue
  fi

  # Must read the code from a production ref.
  check "$f reads gradle.properties from a production ref" \
    "$path" 'git show[^|]*production[^|]*gradle\.properties' present

  # Must NOT fall back to a bare working-tree read for the version.
  check "$f does not grep the working-tree gradle.properties for versionCode" \
    "$path" "^[^#]*grep[^|]*versionCode[^|]*['\\\"]?gradle\.properties" absent
done

# The LOCKSTEP contract has to survive this change: both files must still
# derive the same version name the same way.
assertions=$((assertions + 1))
if grep -q 'lockstep' "$scripts_dir/check-shizu-manifest.sh" \
   && grep -q -i 'lockstep' "$scripts_dir/sync-shizu-changelog.sh"; then
  echo "  ok: both scripts still carry the LOCKSTEP notice"
else
  echo "  FAIL: the LOCKSTEP notice must be in BOTH files, not just one"
  failed=1
fi

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
```

- [ ] **Step 2: Run it to verify it fails**

```bash
chmod +x .github/scripts/test/test-shizu-version-source.sh
.github/scripts/test/run-tests.sh
```

Expected: FAIL — both scripts read the working tree, and `sync-shizu-changelog.sh` may not carry the
LOCKSTEP notice.

- [ ] **Step 3: Add a shared resolver to `check-shizu-manifest.sh`**

Replace the `versionCode` read inside the block at `.github/scripts/check-shizu-manifest.sh:245-262`
with:

```bash
  # shizu_store.json's download_url is /releases/latest/, which GitHub
  # resolves to the newest NON-pre-release - production's build. dev and
  # master both mint pre-releases, so their gradle.properties is one or more
  # codes ahead of what that URL actually serves. Read the code from
  # production so the changelog we assert matches the APK a user downloads.
  #
  # LOCKSTEP: this block (production ref, versionCode grep, version
  # arithmetic, notes fallback path) is kept in lockstep with
  # sync-shizu-changelog.sh - update both scripts together.
  production_ref="${SHIZU_VERSION_REF:-origin/production}"
  if ! git rev-parse --verify --quiet "$production_ref" >/dev/null; then
    # A shallow or single-branch clone will not have it. Fetch just that ref.
    git fetch --quiet --depth=1 origin production 2>/dev/null || true
    production_ref="FETCH_HEAD"
  fi

  version_code="$(git show "${production_ref}:gradle.properties" 2>/dev/null \
    | grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' \
    | head -n 1 | cut -d= -f2 | tr -d '[:space:]')"

  if [ -z "$version_code" ]; then
    echo "::error::could not read versionCode from ${production_ref}:gradle.properties" >&2
    exit 1
  fi

  version_name="$((version_code / 1000)).$(((version_code % 1000) / 10)).$((version_code % 10))"
```

Leave the notes-path resolution below it (`release-notes/v$version_name/playstore.txt` with the
non-`v` fallback) exactly as it is — only the source of `version_code` changes.

- [ ] **Step 4: Apply the identical change to `sync-shizu-changelog.sh`**

Find the matching block in `.github/scripts/sync-shizu-changelog.sh` and replace its `versionCode`
read with the same code, comment included. The two blocks must be textually identical apart from
surrounding indentation — that is what the LOCKSTEP notice is asserting.

Verify:

```bash
diff <(grep -A2 'LOCKSTEP' .github/scripts/check-shizu-manifest.sh) \
     <(grep -A2 -i 'LOCKSTEP' .github/scripts/sync-shizu-changelog.sh) \
  && echo "lockstep notices match"
```

- [ ] **Step 5: Run the tests**

```bash
.github/scripts/test/run-tests.sh
shellcheck -x -S style .github/scripts/check-shizu-manifest.sh \
                       .github/scripts/sync-shizu-changelog.sh \
                       .github/scripts/test/test-shizu-version-source.sh
```

Expected: all assertions `ok:`, shellcheck clean.

- [ ] **Step 6: Run the checker for real**

```bash
git fetch origin production
.github/scripts/check-shizu-manifest.sh
```

Expected: exit 0. It will now report the version derived from `origin/production` — currently
`1.94.0` from code `1940`. If it reports a *different* version than before this change, that
difference is the bug being fixed; note both numbers in the commit message.

- [ ] **Step 7: Ensure CI can see `origin/production`**

`pr-ci.yml`'s checkout must fetch enough history for `git rev-parse origin/production` to resolve.
Confirm the `shizu-manifest` job's checkout step (near `.github/workflows/pr-ci.yml:148`) and add
`fetch-depth: 0` if it is shallow. The script's `git fetch --depth=1` fallback covers the case where
it is not, but an explicit fetch is cheaper than a fallback that runs on every PR.

- [ ] **Step 8: Commit**

```bash
git add .github/scripts/check-shizu-manifest.sh \
        .github/scripts/sync-shizu-changelog.sh \
        .github/scripts/test/test-shizu-version-source.sh \
        .github/workflows/pr-ci.yml
git commit -m "fix(shizu): derive the version from production, not the working tree

shizu_store.json's download_url is /releases/latest/, which GitHub resolves
to the newest NON-pre-release. Under the three-rung ladder that is
production's build, while dev and master both mint pre-releases - so their
gradle.properties runs one or more codes ahead of the APK that URL actually
serves, and the changelog written from it was a beta's notes attached to a
stable's APK.

Both scripts now read gradle.properties from origin/production, with a
--depth=1 fetch fallback for shallow clones.

Applied to both files together, per the LOCKSTEP comment the checker already
carried - and the new test asserts that notice stays in both, since one copy
of a lockstep contract is not a contract."
```

---

### Task 13: Documentation for the ladder

**Files:**
- Modify: `release-notes/README.md`
- Modify: `web/src/lib/repo-facts/parse.ts:39-40`
- Delete: `docs/follow-ups/two-branches-one-play-version-code.md`
- Delete: `docs/follow-ups/telegram-caption-length-guard.md`
- Modify: `docs/follow-ups/README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything Tasks 6–12 produced.
- Produces: nothing. This is the phase's documentation deliverable, and it is part of Phase 2 rather
  than a follow-up because `release-notes/README.md` is the file a human reads before cutting a
  release. Leaving it describing the old routing for even one release is how the old routing gets
  re-applied by hand.

- [ ] **Step 1: Rewrite the routing section of `release-notes/README.md`**

Replace whatever describes the ends-in-`0` rule with:

```markdown
## Which merge publishes what

Three branches, three rungs, one Play upload. A rung is identified by the branch it runs on —
there is no arithmetic on the version number anywhere in the routing.

| merge | workflow | Play | GitHub | Telegram |
|---|---|---|---|---|
| `<feature>` → `dev` | `1-dev-publish.yml` | **uploads** to `alpha` (Closed testing) | pre-release `v<name>-dev-<run>` | yes |
| `dev` → `master` | `2-master-promote.yml` | promotes `alpha` → `beta` (Open testing) | pre-release `v<name>-beta-<run>` | no |
| `master` → `production` | `3-production-promote.yml` | promotes `beta` → `production` | **release** `v<name>` (Latest) | yes |

Only `1-dev-publish.yml` uploads an artifact. Play allows one upload per version code per app —
not per track — so a second uploader is what produced `Version code NNNN has already been used`.
The other two rungs move that same upload up a track.

Every rung still builds the APKs from its own commit: the GitHub assets, the Telegram broadcast
and reproducibility all need an APK built from the tree being released. Only the Play artifact is
promoted rather than rebuilt.

A push that does not change `versionCode` still builds, but publishes nothing — except on
`production`, where it is an error, because there is nothing to promote.

### Mid-cycle bug fixes

**There is no hotfix bypass.** A fix lands on `dev` with a *new* version code and re-enters at the
bottom rung, superseding the previous candidate on `alpha`, then climbs as usual.

This is deliberate rather than an oversight. A fast lane straight to `beta` or `production` would
be a second Play uploader, and one code with two uploaders is the entire problem the ladder
exists to remove. A fix that genuinely cannot wait for the ladder is a fix that should ship as a
new version, which is what this does.
```

- [ ] **Step 2: Add the notes-authoring rule**

`require_notes` is `true` only on the production rung. Add, immediately after the table:

```markdown
### When notes are required

Curated notes (`release-notes/v<name>/`) are **required** for a `production` release and optional
below it. A dev or beta build with no notes directory falls back to the commit log; a production
push with none fails before it builds.

Sizes are checked pre-flight by `.github/scripts/check-notes-budget.sh`:

- `telegram.md` — the **assembled** caption must fit 1024 UTF-16 units. The wrapper is ~149 units,
  so budget ~870 for the file. Telegram *rejects* an oversized caption rather than truncating it.
- `playstore.txt` — under 500 characters.

Run it yourself before opening the release PR:

```bash
.github/scripts/check-notes-budget.sh 1.94.1 149
```
```

- [ ] **Step 3: Document the back-merge**

The README stops at Step 7 and never mentions that `dev` sits behind `master` after a release.
Append:

```markdown
## After the release: back-merge

`master` and `production` each gain a merge commit that `dev` does not have. Bring `dev` back
level before starting the next cycle:

```bash
git checkout dev && git pull
git merge --no-ff origin/master -m "Merge branch 'master' into dev"
git push origin dev
```

This push goes straight to `dev`, which the `DevRules` ruleset permits through a RepositoryRole
bypass. It is the one exception to "never push directly to `dev`". Skipping it means the next
feature branch forks from a tree that is missing the release commit.
```

- [ ] **Step 4: Fix the stale workflow names in the web fact-parser**

`web/src/lib/repo-facts/parse.ts:39-40` names `dev-check.yml` and/or `production-deploy.yml`, both
of which no longer exist. Update the references to the new filenames. Then:

```bash
cd web && npm run build
```

Expected: the full chain (`check:types`, `astro build`, `check:links`, `check:claims`,
`check:markup`, `check:sitemap`, `check:screenshots`) passes. If `check:claims` fails, a claim in
`web/src/content/claims.mjs` still cites the old routing — fix the claim, not the checker.

- [ ] **Step 5: Retire the two resolved follow-ups**

Both are now superseded by shipped code:

- `docs/follow-ups/two-branches-one-play-version-code.md` — the collision it documents cannot occur
  with one uploader.
- `docs/follow-ups/telegram-caption-length-guard.md` — implemented in Task 9.

The retention rule has three exceptions, so **check inbound links before deleting either**:

```bash
git grep -n 'two-branches-one-play-version-code\|telegram-caption-length-guard' -- \
  ':!docs/follow-ups/two-branches-one-play-version-code.md' \
  ':!docs/follow-ups/telegram-caption-length-guard.md'
```

Any hit outside the files themselves must be updated first. The two deleted workflows cited the
first doc; those citations went with them in Task 11. Then:

```bash
git rm docs/follow-ups/two-branches-one-play-version-code.md \
       docs/follow-ups/telegram-caption-length-guard.md
```

Remove both rows from `docs/follow-ups/README.md`. That file is what gets read before picking up
work and has drifted from its own docs before — re-verify each remaining row against its linked
doc while you are in there, and fix any that no longer match.

- [ ] **Step 6: Update `CLAUDE.md`**

`CLAUDE.md` documents the build and versioning but not the release routing, which is the thing
most likely to be got wrong. Add after the Versioning section:

```markdown
## Release routing

Three branches, three rungs. `dev` uploads to Play `alpha`; `master` promotes `alpha` → `beta`;
`production` promotes `beta` → `production`. **Only the `dev` rung uploads an artifact** — Play
allows one upload per version code per app, so a second uploader is an error, not a shortcut.

All three rungs build the APKs from their own commit; only the Play artifact is promoted rather
than rebuilt. Consequence: the three GitHub releases for one version carry **different** APK
bytes, because AGP embeds the build commit in `META-INF/version-control-info.textproto` and each
rung is a different commit. This is expected and is not a signing or reproducibility problem —
IzzyOnDroid rebuilds at the tag, and the tag is pinned to its own commit via `target_commitish`.

`.github/workflows/release-rung.yml` holds the shared implementation; the three
`N-<branch>-*.yml` files are thin callers that differ only in declared inputs. Add an input rather
than forking the rung.
```

- [ ] **Step 7: Verify no doc still describes the old routing**

```bash
git grep -rniI 'ends in 0\|ends with 0\|dev-check\.yml\|production-deploy\.yml' \
  -- ':!docs/superpowers/' ':!release-notes/v*' \
  || echo "clean: no stale routing references outside specs and shipped notes"
```

Expected: `clean: …`. Historical release notes under `release-notes/v*/` are a record of what was
true then and are deliberately excluded, as are the spec and plan in `docs/superpowers/`.

- [ ] **Step 8: Commit**

```bash
git add release-notes/README.md \
        docs/follow-ups/README.md \
        docs/follow-ups/two-branches-one-play-version-code.md \
        docs/follow-ups/telegram-caption-length-guard.md \
        web/src/lib/repo-facts/parse.ts \
        CLAUDE.md
git commit -m "docs: describe the three-rung ladder

release-notes/README.md is the file a human reads before cutting a release,
so it is updated in the same phase that changes the routing rather than as a
follow-up - leaving it describing the digit rule for even one release is how
the digit rule gets re-applied by hand.

Also documents the back-merge, which no doc has ever covered: after a release
dev sits behind master, and the fix is a direct push to dev through the
DevRules bypass. That is the one exception to 'never push directly to dev',
and it was previously convention held in one person's head.

CLAUDE.md gains a Release routing section, including the consequence that
surprises people: the three GitHub releases for one version carry different
APK bytes, because AGP embeds the build commit and each rung is a different
commit.

Retires two follow-ups whose subjects are now shipped code. Inbound links
checked first - the retention rule has three exceptions."
```

---

## Phase 3 — FOSS store fronts

Phase 3 depends on Phase 2 having shipped **and having produced at least one production release**,
because every store front here resolves `/releases/latest/` or clones at a tag. Do not start
Task 14 until a `v<name>` release exists that was minted by `3-production-promote.yml`.

Tasks 14–17 are independent of each other and may be done in any order or in parallel. They are
ordered by how quickly they pay off.

---

### Task 14: Obtainium — a pinned, shareable config

**Files:**
- Create: `docs/obtainium.md`
- Modify: `README.md` — the install section
- Modify: `web/src/content/` — the download page's Obtainium entry
- Create: `.github/scripts/test/test-obtainium-config.sh`

**Interfaces:**
- Consumes: a production release minted by Task 11's `3-production-promote.yml`; the `-foss` suffix
  removal from Task 5.
- Produces: nothing consumed by later tasks.

**Why a pinned config rather than "paste the repo URL".** With no config, Obtainium attaches to the
repo, sees two APK assets on every release and asks the user which one — every update, forever. It
also derives the version from the tag `v1.94.0` while the APK declares `1.94.0`, so its
version-detection check flags a mismatch and the app shows as perpetually updatable.
`apkFilterRegEx` fixes the first; `versionExtractionRegEx` fixes the second.

**Task 5 is a hard prerequisite.** While `versionNameSuffix = "-foss"` exists, the FOSS APK declares
`1.94.0-foss` and no tag-derived string will ever equal it. Obtainium's fallback is
`versionDetection: false`, which trusts the tag blindly and silently stops detecting real updates if
a tag is ever re-cut. Dropping the suffix is what makes strict detection usable.

- [ ] **Step 1: Build the config in Obtainium and export it**

Do not hand-write the JSON. Obtainium's `additionalSettings` key set is version-dependent, and a
hand-written blob with a stale key set is accepted silently and then partly ignored.

On a device with Obtainium installed:

1. Add app → `https://github.com/trinadhthatakula/Thor`
2. Set **Filter APKs by regular expression** to `foss-release\.apk$`
3. Set **Version extraction regular expression** to `^v(.*)$` and **Match group to use** to `$1`
4. Leave **Include prereleases** OFF — under the ladder, only `production` mints a non-pre-release
5. Turn **Fallback to older releases** ON
6. Save, then App menu → **Share** → **App config**

That yields an `obtainium://app/<url-encoded-json>` URI. Keep it verbatim.

Sanity-check the decoded JSON against this shape before committing it — the top-level keys are
stable across versions even though `additionalSettings` is not:

```json
{
  "id": "com.valhalla.thor",
  "url": "https://github.com/trinadhthatakula/Thor",
  "author": "trinadhthatakula",
  "name": "Thor",
  "preferredApkIndex": 0,
  "additionalSettings": "{…\"apkFilterRegEx\":\"foss-release\\\\.apk$\",\"versionExtractionRegEx\":\"^v(.*)$\",\"matchGroupToUse\":\"$1\",\"includePrereleases\":false,\"fallbackToOlderReleases\":true…}",
  "overrideSource": "GitHub"
}
```

If `id` is absent or `apkFilterRegEx` is empty, the export was taken before the settings were saved.
Redo it.

- [ ] **Step 2: Write `docs/obtainium.md`**

Create `docs/obtainium.md`. The template below contains one substitution point, `<ENCODED>`:
replace it with the exported `obtainium://app/…` URI, percent-encoded so it survives being a query
parameter. Produce that encoding mechanically rather than by hand — a stray unescaped `&` or `#`
truncates the config silently and Obtainium opens with a partial app:

```bash
python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' \
  'obtainium://app/…paste the exported URI here…'
```

```markdown
# Installing Thor with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs and updates apps straight from their
GitHub releases — no store account, no telemetry, and the APK you get is the one CI built.

## One-tap install

**[Add Thor to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=<ENCODED>)**

That link opens Obtainium with everything pre-filled. The redirect host is Obtainium's own; it
exists because Android will not follow a custom `obtainium://` scheme from inside every browser.

## Manual setup

If you would rather not follow a link, add `https://github.com/trinadhthatakula/Thor` in Obtainium
and set:

| Setting | Value | Why |
|---|---|---|
| Filter APKs by regular expression | `foss-release\.apk$` | Every release carries two APKs. Without this, Obtainium asks which one on every single update. |
| Version extraction regular expression | `^v(.*)$` | Tags are `v1.94.0`; the APK declares `1.94.0`. Without this, Obtainium reports a version mismatch forever. |
| Match group to use | `$1` | The part of the tag after the `v`. |
| Include prereleases | off | Thor's pre-releases are its closed and open test builds. Turn this on only if you want them. |
| Fallback to older releases | on | Lets Obtainium find the newest release that has a matching APK. |

## Which APK is which

| Asset | Contents |
|---|---|
| `foss-release.apk` | The FOSS build. No Play Billing, no proprietary dependencies. **This is the one Obtainium should install.** |
| `store-release.apk` | The Play Store build. Identical features plus the in-app donation flow, which needs Play Billing. |

The two are signed with the same key, so switching between them does not need an uninstall.

## Release channels

| GitHub release | What it is |
|---|---|
| `v1.94.0` (Latest) | Stable. What Obtainium installs by default. |
| `v1.94.0-beta-12` | Open testing. Same build Play serves to open testers. |
| `v1.94.1-dev-108` | Closed testing. Newest code, least soak time. |

Turning **Include prereleases** on gets you the beta and dev builds too. They are ordinary builds
from the same pipeline, not debug builds — but they have had less time on real devices.
```

- [ ] **Step 3: Add a guard so the docs cannot drift from the assets**

Create `.github/scripts/test/test-obtainium-config.sh`:

```bash
#!/usr/bin/env bash
# The Obtainium config names asset filenames and a tag shape. Both are
# produced by release-rung.yml, so a change there must not silently
# invalidate the published config.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
doc="$repo_root/docs/obtainium.md"
rung="$repo_root/.github/workflows/release-rung.yml"
assertions=0
failed=0

expect_in() {
  local label="$1" file="$2" needle="$3"
  assertions=$((assertions + 1))
  if grep -qF -- "$needle" "$file"; then
    echo "  ok: $label"
  else
    echo "  FAIL: $label - '$needle' not found in $(basename "$file")"
    failed=1
  fi
}

expect_in "docs exist and name the foss asset" "$doc" 'foss-release.apk'
expect_in "docs name the store asset" "$doc" 'store-release.apk'
expect_in "docs pin the APK filter" "$doc" 'foss-release\.apk$'
expect_in "docs pin the version extraction regex" "$doc" '^v(.*)$'

# Both filenames the doc promises must actually be attached by the rung.
expect_in "rung attaches foss-release.apk" "$rung" 'foss-release.apk'
expect_in "rung attaches store-release.apk" "$rung" 'store-release.apk'

# The doc tells users to leave prereleases off, which only works if exactly
# one rung publishes a non-pre-release.
assertions=$((assertions + 1))
non_pre=$(grep -c 'prerelease: false' "$repo_root"/.github/workflows/*-*.yml || true)
if [ "$non_pre" -eq 1 ]; then
  echo "  ok: exactly one rung publishes a non-pre-release"
else
  echo "  FAIL: expected exactly 1 rung with 'prerelease: false', found $non_pre"
  failed=1
fi

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
```

- [ ] **Step 4: Run the tests**

```bash
chmod +x .github/scripts/test/test-obtainium-config.sh
.github/scripts/test/run-tests.sh
```

Expected: PASS — 7 assertions.

- [ ] **Step 5: Verify the link end-to-end on a device**

Open the `apps.obtainium.imranr.dev/redirect` link on an Android device with Obtainium installed.

Expected: Obtainium opens with Thor pre-filled, resolves the newest `v<name>` release, offers
exactly **one** APK (no picker), and shows the installed version as equal to the release version
rather than "update available".

If it offers a picker, `apkFilterRegEx` did not survive the export. If it shows a spurious update,
Task 5 has not landed or `matchGroupToUse` is wrong.

- [ ] **Step 6: Link it from the README and the site**

In `README.md`, add Obtainium to the install options alongside the existing ones, linking to
`docs/obtainium.md` and to the one-tap redirect.

On the site, add the same entry to the download page under `web/src/content/`. Then:

```bash
cd web && npm run build
```

Expected: the full check chain passes. `check:links` will fetch the redirect URL — if it fails,
confirm the URL is percent-encoded exactly as exported; an un-encoded `#` or `&` truncates it.

- [ ] **Step 7: Commit**

```bash
git add docs/obtainium.md README.md web/src/content/ \
        .github/scripts/test/test-obtainium-config.sh
git commit -m "docs(obtainium): publish a pinned, shareable install config

Without a config Obtainium attaches to the repo, sees two APK assets on
every release and asks which one on every update, and derives the version
from the tag v1.94.0 while the APK declares 1.94.0 - so it reports an update
forever. apkFilterRegEx fixes the first, versionExtractionRegEx the second.

The config was exported from Obtainium itself rather than hand-written: its
additionalSettings key set is version-dependent, and a blob with a stale key
set is accepted silently and then partly ignored.

Dropping versionNameSuffix '-foss' is what makes strict version detection
usable at all - while it existed the APK declared 1.94.0-foss and no
tag-derived string could ever equal it.

The test asserts the doc's filenames against what release-rung.yml actually
attaches, and that exactly one rung publishes a non-pre-release - which is
what makes 'leave Include prereleases off' correct advice."
```

---

### Task 15: IzzyOnDroid — fix the mislabelled release and notify the recipe changes

**Files:**
- Modify: GitHub release `v1.81.9-dev-82` (metadata only, via `gh`)
- Create: `docs/izzyondroid-notes.md`

**Interfaces:**
- Consumes: the tag shape and asset list from Tasks 3 and 11; the `-foss` suffix removal from Task 5.
- Produces: nothing consumed by later tasks.

Thor is already listed on IzzyOnDroid with a reproducible-build recipe. Three things this plan
changes are visible to that recipe, and one pre-existing defect feeds it bad input.

**The pre-existing defect.** `v1.81.9-dev-82` has `prerelease=false`. It is a dev build wearing a
stable's label. It is not currently served as `/releases/latest/` — newer stables outrank it — but
IzzyOnDroid's `GHSkipPre` and Obtainium's "include prereleases: off" both decide by that flag, and
Obtainium's `fallbackToOlderReleases` will walk back to it if a newer release ever lacks a matching
asset. Fix the flag.

- [ ] **Step 1: Confirm the mislabelling before changing anything**

```bash
gh release view v1.81.9-dev-82 --json tagName,isPrerelease,isLatest,publishedAt
gh release list --limit 40 --json tagName,isPrerelease,isLatest \
  --jq '.[] | select(.isPrerelease == false) | "\(.tagName)\tlatest=\(.isLatest)"'
```

Expected: `v1.81.9-dev-82` shows `isPrerelease: false`, and the second command lists it among the
non-pre-releases alongside the genuine stables. Record that list — it is the set of releases every
FOSS store front treats as stable.

If any **other** `-dev-` or `-beta-` tag appears in that list, it has the same defect. Fix all of
them in Step 2, not just this one.

- [ ] **Step 2: Relabel it**

```bash
gh release edit v1.81.9-dev-82 --prerelease
```

Then re-run the second command from Step 1 and confirm only genuine `v<name>` tags remain.

This changes release metadata on a public repo. It removes nothing: assets, tag and body are
untouched, and anyone holding a direct asset URL is unaffected.

- [ ] **Step 3: Write the notification notes**

Create `docs/izzyondroid-notes.md`:

```markdown
# IzzyOnDroid — recipe notes

Thor is listed on IzzyOnDroid with a reproducible-build recipe (`rbtlog`), which clones the repo at
the release tag, asserts `git rev-parse HEAD` equals the commit the APK embeds in
`META-INF/version-control-info.textproto`, rebuilds, and compares the whole file with the signature
block stripped.

## Changes that affect the recipe

Three, all introduced by the three-rung release ladder:

**1. `versionName` no longer carries a `-foss` suffix.** The FOSS build used to declare
`1.94.0-foss`; it now declares `1.94.0`, matching the tag and the Play build. Any
`CurrentVersion:`/`AutoUpdateMode:` entry carrying `-foss` needs updating.

**2. One new tag shape: `-beta-N`.** Alongside `v1.94.0` and the existing `v1.94.1-dev-108` (closed
testing) there is now `v1.94.0-beta-12` (open testing). All pre-releases are flagged `prerelease` on
GitHub, so `GHSkipPre` excludes them from `Method: github-release`.

We do not expect this to need any change: `-dev-N` tags have existed for many releases and the
recipe has tracked Thor correctly throughout, and `-beta-N` is the same shape from the same
pipeline. Flagging it only so it is not a surprise. If `UpdateCheckMode: Tags` does turn out to
report a beta version, a pattern would constrain it:

    UpdateCheckMode: Tags ^v[0-9.]+$

**3. The `.aab` is no longer attached to GitHub releases.** It was only ever useful to Play, which
receives it directly from CI. `ApkMatch` already pins `foss-release.apk`, so this only reduces
ambiguity.

**4. `target_commitish` now pins each tag to its build commit.** Thor's rbtlog recipe carries
`git reset --soft` patches that exist to reconcile a tag with the commit the APK claims. Those are
no longer needed — the tag and the embedded commit now agree by construction. Removing them is
optional; leaving them in place is harmless.

## Why three releases for one version can differ byte-for-byte

Each rung builds from its own commit, and AGP embeds that commit in the APK. `v1.94.0-dev-108`,
`v1.94.0-beta-12` and `v1.94.0` therefore contain different `version-control-info.textproto` bytes
even when the source trees are identical.

This does not affect reproducibility: rbtlog clones **at the tag**, and each tag is pinned to its
own build commit via `target_commitish`. Rebuilding `v1.94.0` reproduces `v1.94.0`.

## Contact

Recipe questions go to the IzzyOnDroid repo issue tracker:
<https://gitlab.com/IzzyOnDroid/repo/-/issues>
```

- [ ] **Step 4: Verify the reproducibility claim on the newest stable**

Before notifying anyone, confirm the tag actually points at the built commit:

```bash
TAG=$(gh release list --limit 40 --json tagName,isPrerelease \
        --jq 'map(select(.isPrerelease == false)) | .[0].tagName')
echo "checking $TAG"
gh release download "$TAG" -p 'foss-release.apk' -D /tmp/thor-repro --clobber
unzip -p /tmp/thor-repro/foss-release.apk META-INF/version-control-info.textproto | strings | grep -o '[0-9a-f]\{40\}'
git rev-parse "$TAG^{commit}"
```

Expected: the 40-hex string from the APK equals `git rev-parse`'s output.

If they differ, `target_commitish` did not take effect for that release — stop and fix Task 10
before notifying IzzyOnDroid, because a drifted tag is a failed rbtlog check on their side.

If `version-control-info.textproto` is absent, AGP's `vcsInfo` was disabled for that build; check
`app/build.gradle.kts` for an `vcsInfo { enabled = false }` block, which the design decided to keep
**on**.

- [ ] **Step 5: Open the notification issue**

File an issue on <https://gitlab.com/IzzyOnDroid/repo/-/issues> whose body is the contents of
`docs/izzyondroid-notes.md`, titled:

`com.valhalla.thor: versionName no longer has -foss suffix; new prerelease tag shapes`

This is an outward-facing action on a third party's tracker. **Confirm with the repo owner before
filing**, and let them file it if they prefer — the maintainer relationship is theirs.

- [ ] **Step 6: Commit**

```bash
git add docs/izzyondroid-notes.md
git commit -m "docs(izzy): record the recipe changes and fix a mislabelled release

v1.81.9-dev-82 carried prerelease=false - a dev build wearing a stable's
label. It is not served as /releases/latest/ since newer stables outrank it,
but IzzyOnDroid's GHSkipPre and Obtainium's include-prereleases setting both
decide by that flag, and Obtainium's fallbackToOlderReleases will walk back
to it. Relabelled with gh release edit --prerelease; assets, tag and body
untouched.

Three ladder changes reach the rbtlog recipe: the -foss suffix is gone from
versionName, there are new -dev-N and -beta-N tag shapes, and the .aab is no
longer a release asset.

The one that needs a human decision on their side is UpdateCheckMode: Tags.
It reads tags, and a tag has no prerelease flag - so GHSkipPre does not
protect it and it needs a ^v[0-9.]+$ pattern or it will pick up a beta."
```

---

### Task 16: F-Droid — ask before building

**Files:**
- Rewrite: `docs/fdroid-submission.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing consumed by later tasks.

**Decision 11 from the spec: RFP first, metadata after.** The open question is whether F-Droid's
buildserver can build Thor at all — it pins Gradle 9.6.1 and AGP 9.4.0-alpha07, and F-Droid's
buildserver has historically lagged on alpha AGP. Writing a full metadata file before knowing that
is work that may be thrown away. The RFP asks the question; the metadata follows the answer.

The existing `docs/fdroid-submission.md` is stale — last touched 2026-08-01, written for the
build-from-source route, and it still contains `AutoUpdateMode: Version +-foss` and
`CurrentVersion: 1.93.1-foss`. Both are now wrong.

- [ ] **Step 1: Confirm the toolchain versions the RFP has to state**

```bash
grep -E '^(agp|kotlin|compileSdk|targetSdk|minSdk) *=' gradle/libs.versions.toml
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
grep '^versionCode=' gradle.properties
```

Record the exact values. The RFP is answered by a human reading these numbers, so a stale one wastes
a round trip.

- [ ] **Step 2: Rewrite `docs/fdroid-submission.md`**

Replace the file entirely:

```markdown
# F-Droid submission

**Status: RFP not yet filed.** Decision: ask before building metadata.

## Why RFP first

Thor builds with Gradle 9.6.1 and AGP 9.4.0-alpha07 against `compileSdk`/`targetSdk` 37. F-Droid's
buildserver pins its own toolchain and has historically lagged on alpha AGP releases. If it cannot
build Thor, a complete metadata file is thrown-away work. The RFP asks that question first.

## Route: developer-signed, not build-from-source

Thor requests the RFP under F-Droid's **developer-signed** path — F-Droid ingests the APK this
repo's CI already signs, rather than building and signing its own. That means:

- One signing key across every channel. A user can move between GitHub, Obtainium, IzzyOnDroid,
  F-Droid and the Play Store without an uninstall.
- No divergence between what F-Droid ships and what CI built.
- F-Droid's reproducible-build verification still applies: it rebuilds from source and compares.

The metadata shape for this route uses `Binaries:` plus `Builds[].binary`, with
`AllowedAPKSigningKeys` pinning the signing certificate. Only `%v` (versionName) and `%c`
(versionCode) substitute into the `Binaries` URL.

## The RFP

File at <https://gitlab.com/fdroid/rfp/-/issues>, titled `Thor`. Body:

    ### App name
    Thor

    ### Package ID
    com.valhalla.thor

    ### Description
    An open-source Android app manager: freeze, suspend, force-stop, clear cache and uninstall
    apps using root, Shizuku or Dhizuku. GPL-3.0-or-later.

    ### Source
    https://github.com/trinadhthatakula/Thor

    ### License
    GPL-3.0-or-later (SPDX headers throughout; LICENSE at the repo root)

    ### Existing distribution
    GitHub Releases, IzzyOnDroid (with a reproducible-build recipe), Google Play, Shizu Store.

    ### Requested route
    Developer-signed. Our CI signs release APKs with a single key used across every channel, and
    the `foss` build flavour has no proprietary dependencies and no Play Billing. We would prefer
    F-Droid ingest that APK so users can move between channels without an uninstall.
    `AllowedAPKSigningKeys` would pin our certificate.

    ### The question we need answered before writing metadata
    Can the buildserver currently build:

    - Gradle 9.6.1
    - Android Gradle Plugin 9.4.0-alpha07
    - Kotlin 2.4.10
    - compileSdk / targetSdk 37, minSdk 28
    - JDK 21 (Zulu)

    AGP is on an alpha because compileSdk 37 requires it. If the buildserver cannot take an alpha
    AGP, we would rather know now than after submitting a metadata file — and we can discuss
    whether the reproducible-build verification is feasible on that basis.

    ### Anti-features to declare
    The app requests `QUERY_ALL_PACKAGES` (it is an app manager; the whole function is enumerating
    installed apps) and integrates with Shizuku/Dhizuku/root. We are happy to carry whatever
    anti-feature labels you consider accurate.

## After the RFP is answered

Only then write `metadata/com.valhalla.thor.yml`. Two values that are commonly got wrong and are
**already** wrong in this repo's history:

- `AutoUpdateMode: Version` — **not** `Version +-foss`. The `-foss` suffix was removed from
  `versionName`; the FOSS APK now declares a plain `1.94.0`.
- `CurrentVersion:` — a plain `1.94.0`, likewise without a suffix.

Also note that Thor's GitHub releases now include `-dev-N` and `-beta-N` pre-releases. Any update
check that reads **tags** rather than releases needs a `^v[0-9.]+$` pattern; one that reads releases
is already covered by the prerelease flag.

## Not pursuing: Accrescent

Verified against <https://accrescent.app/docs/guide/appendix/requirements.html>: Accrescent bans
"using a proxy like Shizuku" and bans "root access for any functionality". Both are Thor's core
mechanism, not optional extras. It additionally flags `QUERY_ALL_PACKAGES` and
`REQUEST_INSTALL_PACKAGES` for manual review and bans self-updaters.

This is a permanent decline, not a deferral. Do not re-evaluate it without a change to those rules.
```

- [ ] **Step 3: Verify no stale value survived**

```bash
grep -n 'foss' docs/fdroid-submission.md
```

Expected: hits only on `foss` build flavour prose and `AutoUpdateMode: Version` — **no**
`Version +-foss` and no `1.93.1-foss`. If either appears, the rewrite was partial.

```bash
git grep -n '1\.93\.1-foss\|Version +-foss' -- ':!release-notes/' ':!docs/superpowers/' \
  || echo "clean: no stale -foss metadata anywhere"
```

Expected: `clean: …`.

- [ ] **Step 4: File the RFP**

This is an outward-facing action on a third party's tracker and cannot be undone quietly — an RFP
issue is public and indexed. **Get the repo owner's explicit go-ahead, and let them file it if they
prefer.**

Once filed, add the issue URL to the top of `docs/fdroid-submission.md` and change
`**Status: RFP not yet filed.**` to `**Status: RFP filed — <url>. Awaiting buildserver answer.**`

- [ ] **Step 5: Commit**

```bash
git add docs/fdroid-submission.md
git commit -m "docs(fdroid): rewrite for the developer-signed route, RFP first

The old file was written for build-from-source and had gone stale: it still
carried AutoUpdateMode 'Version +-foss' and CurrentVersion 1.93.1-foss, both
of which are wrong now that the -foss suffix is gone from versionName.

Metadata is deliberately not written yet. The open question is whether
F-Droid's buildserver can build Gradle 9.6.1 with AGP 9.4.0-alpha07 - AGP is
on an alpha because compileSdk 37 requires it - and a full metadata file
written before that is answered is work that may be discarded. The RFP asks
it directly.

Developer-signed rather than F-Droid-signed so one key serves every channel
and users can move between GitHub, Obtainium, IzzyOnDroid, F-Droid and Play
without an uninstall.

Accrescent is recorded as a permanent decline, with the source: its
requirements ban Shizuku-style ADB proxies and root outright, which is
Thor's core mechanism rather than an optional extra."
```

---

### Task 17: OpenAPK — the listing

**Files:**
- Create: `docs/openapk-submission.md`

**Interfaces:**
- Consumes: Task 14's asset conventions.
- Produces: nothing.

OpenAPK is a directory, not a build service: it links to a download rather than hosting a rebuild,
so there is no toolchain question to resolve first. It is last because it delivers the least — a
listing and some discovery — and needs nothing from the other three.

- [ ] **Step 1: Gather the listing values**

```bash
grep '^versionCode=' gradle.properties
gh release list --limit 5 --json tagName,isPrerelease,isLatest
ls fastlane/metadata/android/en-US/images/
wc -c fastlane/metadata/android/en-US/short_description.txt \
      fastlane/metadata/android/en-US/full_description.txt
```

The store listing text already exists under `fastlane/metadata/android/en-US/` and is what Play
serves. Reuse it rather than writing new copy — two descriptions that drift is a maintenance cost
with no benefit.

- [ ] **Step 2: Write `docs/openapk-submission.md`**

```markdown
# OpenAPK listing

**Status: not yet submitted.**

OpenAPK (<https://www.openapk.net>) is a directory of open-source Android apps. It links to a
download rather than rebuilding, so unlike F-Droid there is no buildserver question to settle first.

## Submission values

| Field | Value | Source |
|---|---|---|
| App name | Thor | `fastlane/metadata/android/en-US/title.txt` |
| Package | `com.valhalla.thor` | `app/build.gradle.kts` |
| License | GPL-3.0-or-later | `LICENSE` |
| Source | https://github.com/trinadhthatakula/Thor | — |
| Download | https://github.com/trinadhthatakula/Thor/releases/latest | resolves to the newest `production` release |
| Short description | reuse verbatim | `fastlane/metadata/android/en-US/short_description.txt` |
| Full description | reuse verbatim | `fastlane/metadata/android/en-US/full_description.txt` |
| Icon / screenshots | reuse | `fastlane/metadata/android/en-US/images/` |

Reuse the Play listing text rather than writing new copy. Two descriptions that drift is a
maintenance cost with no upside, and the Play copy is the one that already gets reviewed.

## Which APK to point at

`foss-release.apk`. `store-release.apk` carries Play Billing, which is a proprietary dependency and
makes the build a poor fit for an open-source directory.

Note that `/releases/latest` is a *release* page carrying both APKs, not a direct file link. If
OpenAPK wants a direct URL, use:

    https://github.com/trinadhthatakula/Thor/releases/latest/download/foss-release.apk

That form is stable across releases — GitHub resolves `latest` server-side — and it is the same URL
`shizu_store.json` uses.

## Submit

Via the submission form at <https://www.openapk.net> (or the contact address listed there, if the
form is unavailable). Record the submission date and any tracking reference at the top of this file
once submitted.
```

- [ ] **Step 3: Verify the direct download URL actually resolves**

```bash
curl -sIL -o /dev/null -w '%{http_code} %{url_effective}\n' \
  https://github.com/trinadhthatakula/Thor/releases/latest/download/foss-release.apk
```

Expected: `200` and an effective URL under `objects.githubusercontent.com` naming the newest
`v<name>` release. If it 404s, no production release has been minted yet — finish Phase 2 and cut
one before submitting.

- [ ] **Step 4: Submit**

Outward-facing. **Confirm with the repo owner before submitting**, and record the date and any
reference in the file.

- [ ] **Step 5: Commit**

```bash
git add docs/openapk-submission.md
git commit -m "docs(openapk): record the listing values

OpenAPK links to a download rather than rebuilding, so there is no
buildserver question to settle first - which is why this is last rather than
blocked.

The listing reuses fastlane/metadata/android/en-US/ verbatim rather than
introducing new copy. Two descriptions that drift is a maintenance cost with
no upside, and the Play copy is the one that already gets reviewed.

Points at foss-release.apk: the store build carries Play Billing, which is a
proprietary dependency and a poor fit for an open-source directory. The
/releases/latest/download/ form resolves server-side and is stable across
releases - the same URL shizu_store.json already uses."
```

---


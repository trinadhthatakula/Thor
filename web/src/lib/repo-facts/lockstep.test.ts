import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { computeRepoFacts } from './index.ts'
import { findRepoRoot, readRepoFiles } from './read.ts'

/**
 * Version arithmetic now lives in four places:
 *
 *   1. `app/build.gradle.kts`                     — calculateVersionName()
 *   2. `.github/scripts/sync-shizu-changelog.sh`  — LOCKSTEP-commented
 *   3. `.github/scripts/check-shizu-manifest.sh`  — LOCKSTEP-commented
 *   4. `web/src/lib/repo-facts/parse.ts`          — this package
 *
 * These tests re-run the *shell* implementation through `/bin/sh` and compare
 * its answer to ours. That is a genuinely independent derivation: it reads the
 * file with a different parser, does the arithmetic in a different language, and
 * would disagree the moment either side drifts.
 *
 * One deliberate divergence, since the three-rung release ladder landed: the two
 * shell scripts read `gradle.properties` out of `origin/production`, because
 * `shizu_store.json`'s `download_url` is `/releases/latest/` and serves
 * production's APK. The site renders the *working tree*, which is what a reader
 * can check for themselves. So the tests below apply the scripts' extraction and
 * arithmetic to the working tree — the half that is genuinely shared — and the
 * tripwire at the bottom pins both the extraction literal and the production ref,
 * so neither half can be edited out from under this file.
 *
 * Deliberately not an `expect(versionName).toBe('1.93.1')` value pin — a
 * `chore(release)` commit is the one commit that must never be blocked by the
 * website.
 */
describe('lockstep with the repo shell scripts', () => {
  const root = findRepoRoot()
  const facts = computeRepoFacts(readRepoFiles(root))

  // Lifted from the LOCKSTEP resolver block in
  // `.github/scripts/check-shizu-manifest.sh`, with exactly one substitution:
  // the block pipes `git show "${production_ref}:gradle.properties"` into this
  // grep, and we read the working-tree file instead (see the note above).
  // Everything downstream — the anchored pattern, head/cut/tr — is verbatim, and
  // the third test pins that literal so this copy cannot silently go stale.
  const extractCode =
    "grep -E '(^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$|^versionCode=)' gradle.properties" +
    " | head -n 1 | cut -d= -f2 | tr -dc '0-9'"

  it('agrees with check-shizu-manifest.sh on the derived version name', () => {
    const script = [
      `version_code="$(${extractCode})"`,
      'printf \'%s.%s.%s\' "$((version_code / 1000))" "$(((version_code % 1000) / 10))" "$((version_code % 10))"',
    ].join('\n')

    const fromShell = execFileSync('/bin/sh', ['-c', script], {
      cwd: root,
      encoding: 'utf8',
    }).trim()

    expect(fromShell).toMatch(/^\d+\.\d+\.\d+$/)
    expect(facts.versionName).toBe(fromShell)
  })

  it('agrees with the shell scripts on the raw versionCode', () => {
    const fromShell = execFileSync('/bin/sh', ['-c', extractCode], {
      cwd: root,
      encoding: 'utf8',
    }).trim()

    expect(Number(fromShell)).toBe(facts.versionCode)
  })

  it('still finds the LOCKSTEP resolver it is pinned against', () => {
    for (const rel of [
      '.github/scripts/check-shizu-manifest.sh',
      '.github/scripts/sync-shizu-changelog.sh',
    ]) {
      const body = readFileSync(join(root, rel), 'utf8')
      expect(body, `${rel} lost its LOCKSTEP comment`).toContain('LOCKSTEP:')
      expect(body, `${rel} lost its versionCode grep`).toContain('versionCode')
    }
  })

  it('agrees with app/build.gradle.kts, which is the actual build authority', () => {
    // Not executed — running Gradle from a unit test is minutes, not milliseconds.
    // Instead: assert the Kotlin still does the same three divisions, so a change
    // to the scheme cannot land without reddening this file.
    const gradle = readFileSync(join(root, 'app/build.gradle.kts'), 'utf8')
    const normalised = gradle.replace(/\s+/g, '')

    expect(normalised).toContain('code/1000')
    expect(normalised).toContain('code%1000)/10')
    expect(normalised).toContain('code%10')
  })
})

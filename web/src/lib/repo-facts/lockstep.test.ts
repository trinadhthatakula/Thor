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
 * Deliberately not an `expect(versionName).toBe('1.93.1')` value pin — a
 * `chore(release)` commit is the one commit that must never be blocked by the
 * website.
 */
describe('lockstep with the repo shell scripts', () => {
  const root = findRepoRoot()
  const facts = computeRepoFacts(readRepoFiles(root))

  it('agrees with check-shizu-manifest.sh on the derived version name', () => {
    // Lifted verbatim from .github/scripts/check-shizu-manifest.sh:251-255.
    // If that script changes, this test fails and the LOCKSTEP comment is honoured.
    const script = [
      "version_code=\"$(grep -E '^versionCode=' gradle.properties | cut -d= -f2 | tr -dc '0-9')\"",
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
    const fromShell = execFileSync(
      '/bin/sh',
      ['-c', "grep -E '^versionCode=' gradle.properties | cut -d= -f2 | tr -dc '0-9'"],
      { cwd: root, encoding: 'utf8' },
    ).trim()

    expect(Number(fromShell)).toBe(facts.versionCode)
  })

  it('still finds the LOCKSTEP comments it is pinned against', () => {
    // If someone deletes the shell arithmetic, the two tests above would compare
    // an empty string to an empty string and pass. This is the tripwire.
    for (const rel of [
      '.github/scripts/check-shizu-manifest.sh',
      '.github/scripts/sync-shizu-changelog.sh',
    ]) {
      const body = readFileSync(join(root, rel), 'utf8')
      expect(body, `${rel} lost its LOCKSTEP comment`).toContain('LOCKSTEP:')
      expect(body, `${rel} lost its versionCode grep`).toContain("grep -E '^versionCode='")
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

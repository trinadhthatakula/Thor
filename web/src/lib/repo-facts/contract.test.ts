import { describe, expect, it } from 'vitest'
import { computeRepoFacts } from './index.ts'
import { findRepoRoot, readRepoFiles } from './read.ts'

/**
 * Runs against the real repo files, and asserts **shape only — never values**.
 *
 * `expect(versionName).toBe('1.93.1')` would redden the `chore(release)` commit,
 * which is the one commit in this repo that must never be blocked by the website.
 */
describe('repo facts against the real tree', () => {
  const facts = computeRepoFacts(readRepoFiles(findRepoRoot()))

  it('finds the repo root by walking up to settings.gradle.kts', () => {
    expect(findRepoRoot()).toMatch(/[/\\][^/\\]+$/)
  })

  it('derives a three-part version name', () => {
    expect(facts.versionName).toMatch(/^\d+\.\d+\.\d+$/)
  })

  it('reads a plausible versionCode', () => {
    // Agreement between versionCode and versionName is checked in lockstep.test.ts
    // against an *independent* implementation. Asserting `deriveVersionName(code)`
    // against our own `versionName` here would only restate index.ts's own line.
    expect(facts.versionCode).toBeGreaterThanOrEqual(1000)
  })

  it('suffixes the foss flavour name', () => {
    expect(facts.fossVersionName).toBe(`${facts.versionName}-foss`)
  })

  it('orders the SDK levels', () => {
    for (const n of [facts.minSdk, facts.targetSdk, facts.compileSdk]) {
      expect(Number.isInteger(n)).toBe(true)
    }
    expect(facts.minSdk).toBeGreaterThanOrEqual(21)
    expect(facts.minSdk).toBeLessThanOrEqual(facts.targetSdk)
    expect(facts.targetSdk).toBeLessThanOrEqual(facts.compileSdk)
  })

  it('names the minSdk release', () => {
    expect(facts.minSdkAndroidName).toMatch(/^Android /)
  })

  it('reads the extension API version as a semver string', () => {
    expect(facts.extensionApiVersion).toMatch(/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/)
  })

  it('is frozen', () => {
    expect(Object.isFrozen(facts)).toBe(true)
  })
})

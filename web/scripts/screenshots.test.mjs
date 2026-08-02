/**
 * Proves the screenshot gate in both directions, and proves it is *conditional*
 * in both directions too.
 *
 * The second half is the part worth testing. A gate that fails everywhere would
 * have been rejected on sight — `DeviceFrame`'s placeholder exists so a page can
 * reserve a slot before anyone owns a device, and breaking `npm run build` for
 * that is exactly the outcome the component was written to avoid. So the useful
 * assertions are not "it finds placeholders"; they are "it stays quiet on the
 * builds where placeholders are the point, and refuses on the one build a
 * visitor would see".
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { isProductionDeploy, runScreenshotCheck } from './check-screenshots.mjs'

const fixture = (name) => fileURLToPath(new URL(`./fixtures/screenshots/${name}`, import.meta.url))

/** No inherited VERCEL_ENV: a local `npm test` and a Vercel test run must agree. */
const LOCAL = {}
const PREVIEW = { VERCEL_ENV: 'preview' }
const PRODUCTION = { VERCEL_ENV: 'production' }

describe('check-screenshots', () => {
  it('counts placeholders without failing on a local build', () => {
    const { failures, counts, strict } = runScreenshotCheck(fixture('pending'), LOCAL)
    expect(failures).toEqual([])
    expect(strict).toBe(false)
    // Counted, and printed, even when it passes. The number is the whole point
    // of the advisory mode: it is what tells someone reading a build log that
    // the screenshots have not landed yet.
    expect(counts.placeholders).toBe(2)
    expect(counts.pages).toBe(1)
  })

  it('stays advisory on a preview deploy', () => {
    // Preview is where you *want* to see the unfilled slots on a real URL.
    const { failures, strict } = runScreenshotCheck(fixture('pending'), PREVIEW)
    expect(failures).toEqual([])
    expect(strict).toBe(false)
  })

  it('refuses a production deploy that still has placeholders', () => {
    const { failures, counts, strict } = runScreenshotCheck(fixture('pending'), PRODUCTION)
    expect(strict).toBe(true)
    expect(counts.placeholders).toBe(2)
    // One failure per frame, not one per page: "this page has placeholders" does
    // not tell you which capture is missing.
    expect(failures).toHaveLength(2)
    expect(failures.map((f) => f.what)).toEqual([
      expect.stringContaining("Thor's home screen"),
      expect.stringContaining('The Freezer watchlist'),
    ])
    expect(failures[0].where).toBe('index.html')
    expect(failures[0].why).toContain('web/docs/screenshot-checklist.md')
  })

  it('passes a production deploy once every frame has a capture', () => {
    const { failures, counts, strict } = runScreenshotCheck(fixture('captured'), PRODUCTION)
    expect(strict).toBe(true)
    expect(failures).toEqual([])
    expect(counts.placeholders).toBe(0)
  })

  it('does not read prose about the placeholder as a placeholder', () => {
    // The `captured` fixture explains the mechanism in a paragraph, quoted
    // phrase and all. Matching the words rather than the emitted aria-label
    // would make the gate unable to describe itself.
    const html = readFileSync(`${fixture('captured')}/index.html`, 'utf8')
    expect(html).toContain('Screenshot pending')
    expect(runScreenshotCheck(fixture('captured'), PRODUCTION).failures).toEqual([])
  })

  it('fails a directory with no pages, in either mode', () => {
    // The empty scan is not advisory. "Ran before astro build" reports zero
    // placeholders, which is indistinguishable from "all five have landed".
    for (const env of [LOCAL, PRODUCTION]) {
      const { failures } = runScreenshotCheck(fixture('does-not-exist'), env)
      expect(failures).toHaveLength(1)
      expect(failures[0].what).toBe('no HTML pages found')
    }
  })
})

describe('the environment switch', () => {
  it('is off for a local build and a bare CI run', () => {
    expect(isProductionDeploy({})).toBe(false)
    // CI is true for every PR run, so it cannot mean "visitors get this".
    expect(isProductionDeploy({ CI: 'true' })).toBe(false)
    // NODE_ENV is production for any `astro build`, including on a laptop.
    expect(isProductionDeploy({ NODE_ENV: 'production' })).toBe(false)
    expect(isProductionDeploy({ VERCEL_ENV: 'preview' })).toBe(false)
    expect(isProductionDeploy({ VERCEL_ENV: 'development' })).toBe(false)
  })

  it('is on for a Vercel production build', () => {
    expect(isProductionDeploy({ VERCEL_ENV: 'production' })).toBe(true)
  })

  it('can be forced on, which is how the gate is verified without a deploy', () => {
    expect(isProductionDeploy({ REQUIRE_SCREENSHOTS: '1' })).toBe(true)
    expect(isProductionDeploy({ REQUIRE_SCREENSHOTS: '1', VERCEL_ENV: 'preview' })).toBe(true)
    // Exactly '1' — an unset variable read as an empty string must not arm it.
    expect(isProductionDeploy({ REQUIRE_SCREENSHOTS: '' })).toBe(false)
    expect(isProductionDeploy({ REQUIRE_SCREENSHOTS: '0' })).toBe(false)
  })
})

describe('the marker the gate looks for', () => {
  it('is the one DeviceFrame emits', () => {
    // The gate greps built HTML for a string produced by a template literal in
    // a component nobody will think to check when renaming things. If that
    // label is reworded, every page silently reports zero placeholders and the
    // production deploy stops being gated at all.
    const component = readFileSync(
      fileURLToPath(new URL('../src/components/DeviceFrame.astro', import.meta.url)),
      'utf8',
    )
    expect(component).toContain('aria-label={`Screenshot pending: ${alt}`}')
  })
})

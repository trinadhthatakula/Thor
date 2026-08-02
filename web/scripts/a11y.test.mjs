/**
 * The accessibility audit, which is CI-only.
 *
 * These tests exist mostly to prove the plumbing runs at all: axe injected into
 * a jsdom window, a real result set coming back. An audit harness that silently
 * evaluates nothing produces the same clean output as an accessible site, and
 * `dist` does not exist when this suite runs, so the fixtures are the only place
 * that distinction can be drawn.
 *
 * The `color-contrast` assertion below is the load-bearing one. jsdom has no
 * layout and no CSSOM cascade, so that rule can only ever return "incomplete" —
 * never a pass, never a fail. Contrast is covered properly by arithmetic over
 * the token values in `src/lib/tokens/contrast.test.ts`.
 */
import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import axe from 'axe-core'
import { RUN_ONLY, auditHtml, runA11yCheck } from './check-a11y.mjs'

const fixture = (name) => fileURLToPath(new URL(`./fixtures/a11y/${name}`, import.meta.url))

describe('the audit actually audits', () => {
  it('reports the defects in a page built to have them', async () => {
    const { failures, counts } = await runA11yCheck(fixture('violations'))
    expect(counts.pages).toBe(1)
    const ids = failures.map((f) => f.what)
    expect(ids.join(' ')).toContain('html-has-lang')
    expect(ids.join(' ')).toContain('document-title')
    expect(ids.join(' ')).toContain('link-name')
    expect(ids.join(' ')).toContain('image-alt')
  }, 30_000)

  it('passes a page with none of them', async () => {
    const { failures } = await runA11yCheck(fixture('clean'))
    expect(failures).toEqual([])
  }, 30_000)

  it('fails rather than passing when it scanned nothing', async () => {
    const { failures, counts } = await runA11yCheck(fixture('does-not-exist'))
    expect(counts.pages).toBe(0)
    expect(failures[0].what).toBe('no HTML pages found')
  })
})

describe('the runOnly list', () => {
  it('excludes color-contrast, which jsdom can only answer "incomplete" for', () => {
    expect(RUN_ONLY).not.toContain('color-contrast')
  })

  it('names only rules axe actually ships, so a rename cannot silently drop one', () => {
    // A misspelled rule id is not an error to axe — it just runs one fewer rule.
    // Over a few axe upgrades that is how a `runOnly` list becomes decoration.
    const known = new Set(axe.getRules().map((rule) => rule.ruleId))
    expect(RUN_ONLY.filter((id) => !known.has(id))).toEqual([])
  })

  it('stays off even if color-contrast were added to the list by hand', async () => {
    const violations = await auditHtml(
      '<html lang="en"><head><title>t</title></head><body><main><h1 style="color:#eee;background:#fff">t</h1></main></body></html>',
      { runOnly: [...RUN_ONLY, 'color-contrast'] },
    )
    expect(violations.map((v) => v.id)).not.toContain('color-contrast')
  }, 30_000)
})

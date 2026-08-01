/**
 * The meta-test: it checks the fixtures, not the site.
 *
 * A claims rule with no fixtures is a decoration. It might be an unanchored
 * regex that fires on every page, or one written with straight quotes against
 * smartypants output that can never fire at all — and both look exactly like a
 * clean build. The pairing is what turns "we have twelve rules" into "twelve
 * rules were each shown to reject something and accept something".
 *
 * So this fails the suite when a rule arrives without both fixtures, and again
 * when a fixture outlives the rule it was written for. The second direction
 * matters because `claims.test.mjs` iterates over the *rules*: delete a rule and
 * its fixtures simply stop being visited, and a stale directory then sits there
 * looking like coverage of something that is no longer checked.
 */
import { describe, expect, it } from 'vitest'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { extractText, parseHtml } from './lib/dom.mjs'
import { evaluatePage } from './lib/claims-engine.mjs'
import { flatten } from './lib/text.mjs'
import { claimRules } from '../src/content/claims.mjs'

const dir = (rel) => fileURLToPath(new URL(`./fixtures/${rel}`, import.meta.url))
const subdirs = (rel) =>
  readdirSync(dir(rel), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)

const ruleIds = claimRules.map((rule) => rule.id)

const pageText = (id, side) =>
  extractText(parseHtml(readFileSync(dir(`claims/${id}/${side}/index.html`), 'utf8')))
const wordCount = (text) => flatten(text).split(/\s+/).filter(Boolean).length

describe('every claims rule is proved in both directions', () => {
  it.each(ruleIds)('%s has a fail fixture and a pass fixture', (id) => {
    expect(existsSync(dir(`claims/${id}/fail/index.html`))).toBe(true)
    expect(existsSync(dir(`claims/${id}/pass/index.html`))).toBe(true)
  })

  it('has no fixture directory left behind by a deleted rule', () => {
    // claims.test.mjs iterates the rules, so an orphan is never visited and
    // never fails — it just quietly stops being coverage.
    expect(subdirs('claims').filter((name) => !ruleIds.includes(name))).toEqual([])
  })
})

describe('the fixtures say something a person might actually write', () => {
  // The degenerate fixture is a page whose entire body is the banned phrase. It
  // proves the regex matches itself and nothing else — not that the rule
  // survives contact with a paragraph, which is the only place it will ever run.
  it.each(ruleIds)('%s fires on a phrase embedded in surrounding prose', (id) => {
    const text = pageText(id, 'fail')
    const hits = evaluatePage({ rel: 'index.html', text }, claimRules).violations.filter(
      (violation) => violation.ruleId === id,
    )
    expect(hits.length).toBeGreaterThan(0)

    for (const hit of hits) {
      // `require` reports no sentence: it fires on the absence of a correction
      // anywhere on the page, so the page itself is the context.
      const context = hit.kind === 'forbid' ? hit.sentence : flatten(text)
      expect(context).toContain(hit.matched)
      expect(context.length).toBeGreaterThan(hit.matched.length)
      expect(wordCount(context)).toBeGreaterThanOrEqual(6)
    }
  })

  it.each(ruleIds)('%s is corrected by qualifying the claim, not by deleting it', (id) => {
    // Derived from the pair rather than a magic threshold: a `pass` fixture that
    // is shorter than its `fail` has almost certainly dropped the subject
    // instead of stating the true version of it, and a rule cannot over-trigger
    // on copy that no longer mentions what it is about.
    expect(wordCount(pageText(id, 'pass'))).toBeGreaterThanOrEqual(
      wordCount(pageText(id, 'fail')),
    )
  })
})

describe('the other checkers have fixtures too', () => {
  it('covers each markup assertion plus a clean page', () => {
    expect(subdirs('markup').sort()).toEqual([
      'attributes',
      'clean',
      'duplicate-h1',
      'headings',
      'italics',
    ])
  })

  it('covers a11y in both directions', () => {
    expect(subdirs('a11y').sort()).toEqual(['clean', 'violations'])
  })

  it('covers each link failure mode plus the hard passing case', () => {
    expect(subdirs('links').sort()).toEqual([
      'broken-fragment',
      'broken-link',
      'pass',
      'trailing-slash',
    ])
  })

  it('covers each sitemap failure mode plus the agreeing case', () => {
    // Every one of these is invisible on the rendered site, so the fixtures are
    // the only place these rules are ever seen to fire.
    expect(subdirs('sitemap').sort()).toEqual([
      'disallowed-listed',
      'missing-child',
      'missing-index',
      'pass',
      'phantom-url',
      'unlisted-page',
    ])
  })
})

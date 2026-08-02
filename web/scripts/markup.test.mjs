/**
 * The structural gate that runs inside `npm run build`.
 *
 * The no-italics rule is the reason it is on the deploy path rather than in CI:
 * web CI is path-filtered and must never become a required check, so it cannot
 * guard a deploy. Everything asserted here is decidable from markup alone and
 * costs milliseconds, which is what earns it a place between a merge and a
 * production build.
 */
import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { checkPage, runMarkupCheck } from './check-markup.mjs'

const fixture = (name) => fileURLToPath(new URL(`./fixtures/markup/${name}`, import.meta.url))
const messages = (failures) => failures.map((f) => f.what).join('\n')

describe('correct markup does not false-fail', () => {
  it('passes a page with one h1, ordered headings, alt text and a lang', () => {
    const { failures, counts } = runMarkupCheck(fixture('clean'))
    expect(failures).toEqual([])
    expect(counts.pages).toBe(1)
  })

  it('accepts alt="" on a decorative image', () => {
    // The rule is "has an alt attribute", not "has non-empty alt text". An empty
    // alt is the correct answer for a hairline or a spacer, and a checker that
    // rejected it would push authors into writing noise for a screen reader.
    expect(checkPage('x.html', '<html lang="en"><body><h1>t</h1><img src="a" alt=""></body></html>'))
      .toEqual([])
  })
})

describe('the no-italics rule', () => {
  it('fails both em and i', () => {
    const { failures } = runMarkupCheck(fixture('italics'))
    expect(failures).toHaveLength(2)
    expect(messages(failures)).toContain('<em>')
    expect(messages(failures)).toContain('<i>')
  })

  it('quotes the offending text, so the MDX line is findable', () => {
    // A bare "an em was found on /features" is a search through 2,000 words.
    const { failures } = runMarkupCheck(fixture('italics'))
    expect(messages(failures)).toContain('"is"')
  })
})

describe('the structural assertions', () => {
  it('fails a page with no h1 and a skipped heading level', () => {
    const { failures } = runMarkupCheck(fixture('headings'))
    expect(failures).toHaveLength(2)
    expect(messages(failures)).toContain('0 <h1> elements')
    expect(messages(failures)).toContain('h2 -> h4')
  })

  it('fails a page with two h1s', () => {
    const { failures } = runMarkupCheck(fixture('duplicate-h1'))
    expect(failures).toHaveLength(1)
    expect(messages(failures)).toContain('2 <h1> elements')
  })

  it('fails a missing alt attribute and a missing html lang', () => {
    const { failures } = runMarkupCheck(fixture('attributes'))
    expect(failures).toHaveLength(2)
    expect(messages(failures)).toContain('no alt')
    expect(messages(failures)).toContain('no lang')
  })
})

describe('the empty-scan guard', () => {
  it('fails rather than passing when there is nothing to check', () => {
    // This is the one that matters most in the build chain: `check:markup` runs
    // after `astro build`, and if that ever stops being true it must fail rather
    // than report a clean site it never opened.
    const { failures, counts } = runMarkupCheck(fixture('does-not-exist'))
    expect(counts.pages).toBe(0)
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toBe('no HTML pages found')
  })
})

/**
 * The link gate, proved in both directions.
 *
 * The second direction is the one that matters for whether this survives. A
 * checker that fails on correct links gets disabled inside a week — so
 * `links/pass` is deliberately the *hardest* fixture here: directory routes,
 * same-page and cross-page fragments, a relative link that climbs a directory,
 * an internal absolute URL, a legacy `<a name>`, `#top`, a non-HTML asset, and
 * genuinely external links, all of which must come back clean.
 */
import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { classify, readSiteOrigin, resolveRoute, runLinkCheck } from './check-links.mjs'

const SITE_ORIGIN = 'https://thor.trinadhthatakula.com'
const fixture = (name) => fileURLToPath(new URL(`./fixtures/links/${name}`, import.meta.url))

const check = (name) => runLinkCheck(fixture(name), { siteOrigin: SITE_ORIGIN })

describe('correct links do not false-fail', () => {
  it('passes a fixture built out of every internal link shape the site uses', async () => {
    const { failures } = await check('pass')
    expect(failures).toEqual([])
  })

  it('reports how much it actually checked', async () => {
    // The counts are the whole defence against the worst failure mode in this
    // phase: a checker that matched zero files exits 0 and looks like a pass.
    const { counts } = await check('pass')
    expect(counts.pages).toBe(3)
    expect(counts.internal).toBeGreaterThan(0)
    expect(counts.fragments).toBeGreaterThan(0)
  })

  it('resolves the hashed stylesheet and font Astro emits into _astro/', async () => {
    // Regression. `_astro` was on the walker's skip list, which also hid it from
    // the file index, so every page's own stylesheet and every preloaded font
    // reported as a broken link on a build where all of them were present. Eight
    // failures on a correct site is how a gate gets deleted rather than fixed.
    const { failures } = await check('pass')
    expect(failures.filter((f) => f.what.includes('_astro'))).toEqual([])
  })

  it('counts skipped externals separately, so a wrong `site` is visible', async () => {
    // If `site` were ever wrong, every internal absolute URL would classify as
    // external and the checker would pass while checking almost nothing. The
    // only symptom is this pair of numbers moving.
    const { counts } = await check('pass')
    expect(counts.external).toBe(1)
    expect(counts.ignored).toBe(2) // mailto: and tel:
  })
})

describe('broken links fail', () => {
  it('fails a route that does not exist in the build', async () => {
    const { failures } = await check('broken-link')
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toContain('/faqs')
    expect(failures[0].why).toContain('faqs/index.html')
  })

  it('fails a fragment with no matching id, on this page and on another', async () => {
    const { failures } = await check('broken-fragment')
    expect(failures).toHaveLength(2)
    expect(failures.map((f) => f.what).join(' ')).toContain('#i-was-renamed')
    expect(failures.map((f) => f.what).join(' ')).toContain('#you-may-not-need-root')
  })

  it('fails a trailing slash, which trailingSlash: never serves as a redirect', async () => {
    const { failures } = await check('trailing-slash')
    expect(failures).toHaveLength(1)
    expect(failures[0].why).toContain("trailingSlash: 'never'")
  })

  it('fails loudly when the directory has no HTML in it at all', async () => {
    // `check:links` runs after `astro build` in the chain. If that ordering is
    // ever broken, or the output directory moves, this is what says so instead
    // of exiting 0 forever.
    const { failures, counts } = await runLinkCheck(fixture('does-not-exist'), {
      siteOrigin: SITE_ORIGIN,
    })
    expect(counts.pages).toBe(0)
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toBe('no HTML pages found')
  })
})

describe('route resolution follows Astro build.format: directory', () => {
  const files = new Set(['index.html', 'faq/index.html', 'robots.txt', 'flat.html'])

  it('resolves / to the root index', () => {
    expect(resolveRoute('/', files)).toEqual({ ok: true, target: 'index.html' })
  })

  it('resolves /faq to faq/index.html', () => {
    expect(resolveRoute('/faq', files)).toEqual({ ok: true, target: 'faq/index.html' })
  })

  it('resolves an exact file and a flat page too', () => {
    expect(resolveRoute('/robots.txt', files).target).toBe('robots.txt')
    expect(resolveRoute('/flat', files).target).toBe('flat.html')
  })

  it('names everything it tried when nothing matches', () => {
    expect(resolveRoute('/faqs', files)).toEqual({
      ok: false,
      tried: ['faqs', 'faqs/index.html', 'faqs.html'],
    })
  })
})

describe('classification', () => {
  const at = (value, pageRel = 'index.html') => classify(value, { pageRel, siteOrigin: SITE_ORIGIN })

  it('treats an absolute URL on our own origin as internal', () => {
    expect(at(`${SITE_ORIGIN}/faq`)).toMatchObject({ kind: 'internal', pathname: '/faq' })
  })

  it('treats another origin as external', () => {
    expect(at('https://github.com/trinadhthatakula/Thor').kind).toBe('external')
  })

  it('treats a protocol-relative URL as the absolute URL it is', () => {
    expect(at('//example.com/x').kind).toBe('external')
  })

  it('skips mailto and tel', () => {
    expect(at('mailto:a@b.c').kind).toBe('ignored')
    expect(at('tel:+10000000000').kind).toBe('ignored')
  })

  it('short-circuits a bare #fragment to the current page', () => {
    // Resolving it as a path would produce the page's directory URL (`/faq/`),
    // which the trailing-slash rule would then reject — a false failure on the
    // most common correct link on the site.
    expect(at('#anchor', 'faq/index.html')).toMatchObject({
      kind: 'internal',
      samePage: true,
      fragment: 'anchor',
    })
  })

  it('rejects an empty href instead of silently resolving it to this page', () => {
    expect(at('').kind).toBe('empty')
  })
})

describe('the site origin comes from astro.config.mjs', () => {
  it('reads the real config rather than a second copy of the origin', async () => {
    // A retyped origin is a second thing to forget. If these ever disagree,
    // every internal absolute URL classifies as external and the gate stops
    // checking most of the site without failing.
    await expect(readSiteOrigin()).resolves.toBe(SITE_ORIGIN)
  })

  it('refuses to guess an origin when `site` is unset', async () => {
    const empty = fileURLToPath(new URL('./fixtures/links/no-site.config.mjs', import.meta.url))
    await expect(readSiteOrigin(empty)).rejects.toThrow(/has no `site`/)
  })
})

/**
 * Proves the sitemap gate in both directions.
 *
 * Every failure mode here is invisible on the rendered site — the pages all
 * work, the build is green, and the only consequence is how a crawler behaves.
 * So the fixtures are the only place the rules are ever observed to fire.
 */
import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { runSitemapCheck, parseRobots, servedPath, sitemapLocations } from './check-sitemap.mjs'

const fixture = (name) => fileURLToPath(new URL(`./fixtures/sitemap/${name}`, import.meta.url))

describe('check-sitemap', () => {
  it('passes a build whose robots.txt and sitemap chain agree', () => {
    const { failures, counts } = runSitemapCheck(fixture('pass'))
    expect(failures).toEqual([])
    // Both levels were walked, not just the index.
    expect(counts.sitemaps).toBe(2)
    expect(counts.urls).toBe(2)
    // Two pages, not three: 404.html is in the fixture and must be exempt, or
    // this assertion would redden every real build.
    expect(counts.pages).toBe(2)
  })

  it('fails when robots.txt advertises a sitemap that was never emitted', () => {
    const { failures } = runSitemapCheck(fixture('missing-index'))
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toContain('does not exist in the build')
  })

  it('fails when the index names a child that is not in the build', () => {
    const { failures } = runSitemapCheck(fixture('missing-child'))
    // The missing child, and then the empty chain it leaves behind.
    expect(failures.map((f) => f.what)).toEqual([
      expect.stringContaining('which is not in the build'),
      expect.stringContaining('no page URLs'),
    ])
  })

  it('fails when a sitemap lists a path robots.txt disallows', () => {
    const { failures } = runSitemapCheck(fixture('disallowed-listed'))
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toContain('/styleguide/colour')
    expect(failures[0].what).toContain('robots.txt disallows')
  })

  it('fails when the build serves a page the sitemap does not list', () => {
    // The regression that went unnoticed: deleting a <loc> from the emitted
    // sitemap left the gate reporting OK, because every assertion it made was
    // about the sitemap in isolation. The page still works; it just stops being
    // indexed, months before anyone notices.
    const { failures } = runSitemapCheck(fixture('unlisted-page'))
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toContain('does not list /faq')
  })

  it('fails when a sitemap lists a URL the build does not serve', () => {
    // check-links catches a dead internal path only when some page still links
    // to it. Nothing in this fixture links to /handbook, so this gate is the
    // only one that can see it.
    const { failures } = runSitemapCheck(fixture('phantom-url'))
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toContain('/handbook')
    expect(failures[0].what).toContain('the build does not serve')
  })

  it('does not report a disallowed page twice', () => {
    // /styleguide/colour is in the build and in the sitemap, so it is eligible
    // for all three complaints. One wrong URL, one message.
    const { failures } = runSitemapCheck(fixture('disallowed-listed'))
    expect(failures).toHaveLength(1)
  })

  it('fails a directory with no robots.txt at all', () => {
    // Guards the "ran before astro build" case, which otherwise scans nothing
    // and exits 0.
    const { failures } = runSitemapCheck(fixture('does-not-exist'))
    expect(failures).toHaveLength(1)
    expect(failures[0].what).toBe('no robots.txt')
  })
})

describe('the parsers the gate is built on', () => {
  it('ignores a commented-out directive', () => {
    // robots.txt in this repo carries long comment blocks above the real lines,
    // and `# Sitemap: …` in an explanation must not count as a declaration.
    const { sitemaps, disallowed } = parseRobots(
      '# Sitemap: https://example.com/old.xml\nDisallow: /styleguide\nSitemap: https://example.com/new.xml\n',
    )
    expect(sitemaps).toEqual(['https://example.com/new.xml'])
    expect(disallowed).toEqual(['/styleguide'])
  })

  it('reads <loc> regardless of surrounding whitespace', () => {
    expect(sitemapLocations('<loc>a</loc>\n<loc>\n  b\n</loc>')).toEqual(['a', 'b'])
  })

  it('maps a built file to the path it is served at', () => {
    // The two sides of assertion 5 only line up if this agrees with the URLs
    // @astrojs/sitemap emits under `trailingSlash: 'never'`.
    expect(servedPath('index.html')).toBe('/')
    expect(servedPath('faq/index.html')).toBe('/faq')
    expect(servedPath('build-an-extension/index.html')).toBe('/build-an-extension')
    expect(servedPath('404.html')).toBe('/404')
  })
})

/**
 * Proves the sitemap gate in both directions.
 *
 * Every failure mode here is invisible on the rendered site — the pages all
 * work, the build is green, and the only consequence is how a crawler behaves.
 * So the fixtures are the only place the rules are ever observed to fire.
 */
import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { runSitemapCheck, parseRobots, sitemapLocations } from './check-sitemap.mjs'

const fixture = (name) => fileURLToPath(new URL(`./fixtures/sitemap/${name}`, import.meta.url))

describe('check-sitemap', () => {
  it('passes a build whose robots.txt and sitemap chain agree', () => {
    const { failures, counts } = runSitemapCheck(fixture('pass'))
    expect(failures).toEqual([])
    // Both levels were walked, not just the index.
    expect(counts.sitemaps).toBe(2)
    expect(counts.urls).toBe(2)
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
})

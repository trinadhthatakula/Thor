/**
 * Checks that `robots.txt` and the emitted sitemap agree.
 *
 * ## Why this is a gate and not a glance
 *
 * A `Sitemap:` line pointing at a URL that 404s is read by a crawler as "no
 * sitemap" and it falls back to following links. Nothing errors, the build is
 * green, the pages are all reachable, and the only symptom is slower and less
 * complete indexing — months later, with nothing in any log to attribute it to.
 * That is precisely the shape this phase treats as a bug rather than a nit.
 *
 * It has already been wrong once: `robots.txt` advertised
 * `/sitemap-index.xml` while the integration that emits it was commented out of
 * `astro.config.mjs`, because the dependency was not installed. Both files were
 * individually reasonable and the pair was broken.
 *
 * ## What it asserts
 *
 * 1. `robots.txt` exists and declares exactly one `Sitemap:` line.
 * 2. That URL's path resolves to a file that actually exists in `dist`.
 * 3. The index names at least one child sitemap, and every child it names
 *    exists too — an index pointing at a missing `sitemap-0.xml` is the same
 *    silent failure one level down.
 * 4. No `Disallow`d path appears in any sitemap. Advertising a URL you also ask
 *    crawlers not to fetch is a contradiction, and `/styleguide` is excluded in
 *    three independent places precisely because two of them could regress.
 * 5. The sitemap and the build list the same pages, in both directions. Every
 *    page URL resolves to a file `dist` actually serves, and every page in
 *    `dist` appears in the sitemap.
 *
 * Assertion 5 is the one the first four could not make. Everything above treats
 * the sitemap as a self-contained document — is it advertised, does the chain
 * resolve, does it contradict robots.txt — and all four hold perfectly on a
 * sitemap describing a site that does not exist. Deleting `<loc>…/faq</loc>`
 * from the emitted sitemap left this check reporting "OK — 6 page URLs", one
 * fewer than the run before and no complaint about the difference, because the
 * only expectation it had about the count was that it not be zero. A page that
 * drops out of the sitemap is exactly the failure this file opens by describing:
 * nothing errors, the page still works, and the consequence is that it is not
 * indexed.
 *
 * Both directions are needed and neither implies the other. `check-links` covers
 * the phantom-URL direction only by accident — it catches a deleted page because
 * some *other* page linked to it, so a page that is orphaned in the navigation
 * would 404 for crawlers with every gate green.
 */
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
// Shared with the link checker on purpose: "does the build serve this path" has
// to mean the same thing in both gates, and one exported function is the only
// way to guarantee that as the trailing-slash and directory-format rules change.
import { resolveRoute } from './check-links.mjs'
import { relPosix, walkFiles } from './lib/dist.mjs'
import { dirArg, isMain, report } from './lib/report.mjs'

/**
 * The URL path a `<loc>` refers to, normalised for comparison.
 *
 * `astro.config.mjs` sets `trailingSlash: 'never'`, so the emitted URLs carry no
 * trailing slash — but a hand-edited sitemap might, and `/faq` and `/faq/` name
 * the same page. Root stays `/`.
 */
function locPath(loc) {
  const { pathname } = new URL(loc, 'https://example.invalid')
  return pathname === '/' ? '/' : pathname.replace(/\/+$/, '')
}

/**
 * The URL path a built HTML file is served at, under directory format.
 *
 * `index.html` -> `/`, `faq/index.html` -> `/faq`, `foo.html` -> `/foo`.
 */
export function servedPath(rel) {
  if (rel === 'index.html') return '/'
  if (rel.endsWith('/index.html')) return `/${rel.slice(0, -'/index.html'.length)}`
  return `/${rel.slice(0, -'.html'.length)}`
}

/** Every `<loc>` in a sitemap or sitemap index, in document order. */
export function sitemapLocations(xml) {
  return [...xml.matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/g)].map((match) => match[1])
}

/** The `Sitemap:` and `Disallow:` directives in a robots.txt. */
export function parseRobots(text) {
  const sitemaps = []
  const disallowed = []
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/#.*$/, '').trim()
    const sitemap = /^sitemap:\s*(\S+)/i.exec(line)
    if (sitemap) sitemaps.push(sitemap[1])
    const disallow = /^disallow:\s*(\S+)/i.exec(line)
    if (disallow) disallowed.push(disallow[1])
  }
  return { sitemaps, disallowed }
}

export function runSitemapCheck(dir) {
  const failures = []
  const at = (where, what, why) => failures.push({ where, what, why })
  const counts = { sitemaps: 0, urls: 0, pages: 0 }

  const robotsPath = join(dir, 'robots.txt')
  if (!existsSync(robotsPath)) {
    at(
      dir,
      'no robots.txt',
      'public/robots.txt is copied verbatim into the build, so a missing one here means either ' +
        'the check ran before `astro build` or the file left public/.',
    )
    return { failures, counts }
  }

  const { sitemaps, disallowed } = parseRobots(readFileSync(robotsPath, 'utf8'))
  if (sitemaps.length !== 1) {
    at(
      'robots.txt',
      `${sitemaps.length} Sitemap: directives`,
      'Exactly one is expected. Zero means the sitemap is not advertised at all; more than one ' +
        'means two generators are fighting and only one of them is current.',
    )
    return { failures, counts }
  }

  // Resolved as a path so the assertion is about the file that will be served,
  // not about the origin — which differs between a preview deploy and production.
  const indexRel = new URL(sitemaps[0], 'https://example.invalid').pathname.replace(/^\/+/, '')
  if (!existsSync(join(dir, indexRel))) {
    at(
      'robots.txt',
      `Sitemap: ${sitemaps[0]} does not exist in the build`,
      `Nothing was emitted at "${indexRel}". A crawler treats a 404 sitemap as no sitemap and ` +
        'says nothing about it, so this cannot be caught by looking at the site. Either the ' +
        '@astrojs/sitemap integration is not enabled in astro.config.mjs, or it renamed its ' +
        'output and robots.txt still names the old file.',
    )
    return { failures, counts }
  }

  // The index names children; the children name pages. Both levels are checked,
  // because a well-formed index pointing at a missing child fails identically.
  const pending = [indexRel]
  const seen = new Set()
  const pageUrls = []
  while (pending.length > 0) {
    const rel = pending.shift()
    if (seen.has(rel)) continue
    seen.add(rel)
    counts.sitemaps++

    for (const loc of sitemapLocations(readFileSync(join(dir, rel), 'utf8'))) {
      const childRel = new URL(loc, 'https://example.invalid').pathname.replace(/^\/+/, '')
      if (!/\.xml$/i.test(childRel)) {
        pageUrls.push(loc)
        continue
      }
      if (!existsSync(join(dir, childRel))) {
        at(
          rel,
          `names ${loc}, which is not in the build`,
          'A sitemap index that points at a missing child is the same silent failure as a ' +
            'missing index: the crawler gets a 404 and moves on.',
        )
        continue
      }
      pending.push(childRel)
    }
  }
  counts.urls = pageUrls.length

  if (pageUrls.length === 0) {
    at(
      indexRel,
      'no page URLs in any sitemap',
      'The sitemap chain resolved but lists nothing. An empty sitemap is worse than none: it ' +
        'reads as an assertion that the site has no pages.',
    )
  }

  const blockedBy = (path) => disallowed.find((rule) => rule !== '/' && path.startsWith(rule))

  for (const loc of pageUrls) {
    const path = locPath(loc)
    const blocked = blockedBy(path)
    if (blocked === undefined) continue
    at(
      indexRel,
      `lists ${loc}, which robots.txt disallows (${blocked})`,
      'The two files contradict each other. /styleguide is the expected case: it is excluded by ' +
        'getStaticPaths under PROD, by the sitemap `filter`, and by robots.txt — if it reaches a ' +
        'sitemap, the first two of those have both regressed.',
    )
  }

  // --- assertion 5, both directions ---------------------------------------
  //
  // A disallowed path is skipped in both loops. It has already been reported
  // above as a robots.txt contradiction, and one wrong URL should produce one
  // message rather than three.
  const built = walkFiles(dir)
    .filter((file) => file.endsWith('.html'))
    .map((file) => relPosix(dir, file))
  const files = new Set(walkFiles(dir).map((file) => relPosix(dir, file)))

  const listed = new Set(pageUrls.map(locPath))
  for (const loc of pageUrls) {
    const path = locPath(loc)
    if (blockedBy(path) !== undefined) continue
    if (resolveRoute(path, files).ok) continue
    at(
      indexRel,
      `lists ${loc}, which the build does not serve`,
      'A sitemap full of 404s trains a crawler to distrust the whole file. This is usually a page ' +
        'that was renamed or deleted while the sitemap kept the old URL. check-links only catches ' +
        'it when some other page still links to the old path, which is not guaranteed.',
    )
  }

  for (const rel of built) {
    // The error page is served on 404s rather than crawled, and listing it in a
    // sitemap invites indexing it as a page.
    if (rel === '404.html') continue
    const path = servedPath(rel)
    if (blockedBy(path) !== undefined) continue
    counts.pages++
    if (listed.has(path)) continue
    at(
      indexRel,
      `does not list ${path}, which the build serves`,
      `"${rel}" is in the build and nothing in robots.txt excludes it, so its absence from the ` +
        'sitemap is a page asking not to be indexed by accident. The usual causes are a `filter` ' +
        'in astro.config.mjs that is broader than intended, or a page whose route moved without ' +
        'the sitemap being regenerated.',
    )
  }

  return { failures, counts }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts } = runSitemapCheck(dir)
  process.exit(
    report(
      'check-sitemap',
      [
        ['sitemap files', counts.sitemaps],
        ['page URLs', counts.urls],
        ['built pages cross-checked', counts.pages],
      ],
      failures,
    ),
  )
}

/**
 * Offline, first-party internal link and fragment checker over the built site.
 *
 * ## Why this exists rather than lychee
 *
 * The spec recommends `lychee --offline dist` for the build gate. lychee has
 * **no npm distribution** — the npm package of that name is an abandoned 2013
 * database library — and it is not present in Vercel's build image. A build gate
 * that depended on it would depend on a GitHub tarball download succeeding on
 * every deploy, and neither hook that could install it survives:
 *
 *   - a `preinstall` hook is wiped by `npm ci`, which deletes `node_modules`
 *     before running scripts;
 *   - a `postinstall` hook is skipped entirely when Vercel restores a cached
 *     `node_modules`, which is the common case.
 *
 * So the gate would be present on some deploys and absent on others, with no
 * signal telling the two apart. lychee is still the right tool for the *weekly
 * external* sweep, where `lycheeverse/lychee-action` owns installing it and a
 * failure opens an issue instead of blocking a deploy.
 *
 * ## What it checks
 *
 * Every `href` and `src` in the built HTML that resolves to this site: relative
 * paths, absolute paths, same-page `#fragments`, fragments on another page, and
 * absolute URLs whose origin equals `astro.config.mjs`'s `site`. Astro's
 * `build.format: 'directory'` means `/faq` is `dist/faq/index.html`, and
 * `trailingSlash: 'never'` means `/faq/` is a redirect rather than a link — both
 * are honoured.
 *
 * Genuinely external URLs, `mailto:` and `tel:` are skipped and **counted**. The
 * count is load-bearing: if `site` were ever wrong, every internal absolute URL
 * would silently classify as external and the checker would pass while checking
 * nothing. A "42 external skipped, 0 internal checked" line makes that visible.
 */
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { collectAnchors, collectReferences, parseHtml } from './lib/dom.mjs'
import { fileIndex, loadPages } from './lib/dist.mjs'
import { dirArg, emptyScanFailure, isMain, report } from './lib/report.mjs'

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))

/** Schemes that address something other than a page, and are none of our business. */
const IGNORED_SCHEMES = new Set(['mailto:', 'tel:', 'data:', 'blob:', 'javascript:', 'about:'])

/**
 * The configured site origin, read from `astro.config.mjs` itself rather than
 * retyped. Importing the real config is the only way to guarantee the checker
 * and the build agree about what "internal" means; a second copy of the origin
 * is a second thing to forget to update.
 *
 * A failure here is fatal on purpose. Falling back to a default origin would
 * make every internal absolute URL look external and turn the checker off.
 */
export async function readSiteOrigin(configPath = join(SCRIPT_DIR, '..', 'astro.config.mjs')) {
  const module = await import(pathToFileURL(configPath).href)
  const site = module.default?.site
  if (!site) {
    throw new Error(
      'astro.config.mjs has no `site`, so an absolute URL cannot be classified as internal or ' +
        `external. Set it before running check-links (${configPath}).`,
    )
  }
  return new URL(site).origin
}

/**
 * Resolve a site-absolute path to a file in the build.
 *
 * Directory format means three shapes are legitimate and all three are tried,
 * in the order Vercel would serve them: an exact file (`/robots.txt`), a
 * directory index (`/faq` -> `faq/index.html`), and a flat page (`faq.html`).
 */
export function resolveRoute(pathname, files) {
  const clean = pathname.replace(/^\/+/, '')
  if (clean === '') {
    return files.has('index.html')
      ? { ok: true, target: 'index.html' }
      : { ok: false, tried: ['index.html'] }
  }
  const candidates = [clean, `${clean}/index.html`, `${clean}.html`]
  for (const candidate of candidates) {
    if (files.has(candidate)) return { ok: true, target: candidate }
  }
  return { ok: false, tried: candidates }
}

/**
 * Classify one reference into `internal`, `external`, `ignored`, `empty` or
 * `malformed`, resolving relative paths against the page they appear on.
 *
 * A bare `#fragment` short-circuits to the page itself rather than being
 * resolved as a path. Resolving it would produce the page's *directory* URL
 * (`/faq/`), which the trailing-slash rule below would then reject — a
 * false-fail on the most common correct link on the site.
 */
export function classify(value, { pageRel, siteOrigin }) {
  const raw = value.trim()
  if (raw === '') return { kind: 'empty' }

  if (raw.startsWith('#')) {
    return {
      kind: 'internal',
      samePage: true,
      pathname: null,
      fragment: safeDecode(raw.slice(1)),
    }
  }

  // Protocol-relative `//host/path` is an absolute URL wearing a disguise.
  const withScheme = raw.startsWith('//') ? `https:${raw}` : raw
  const scheme = withScheme.match(/^[a-z][a-z0-9+.-]*:/i)?.[0]?.toLowerCase()

  if (scheme && IGNORED_SCHEMES.has(scheme)) return { kind: 'ignored', scheme }

  let url
  if (scheme) {
    try {
      url = new URL(withScheme)
    } catch {
      return { kind: 'malformed' }
    }
    if (url.origin !== siteOrigin) return { kind: 'external', origin: url.origin }
  } else {
    // The origin here is a placeholder; only the pathname and hash are used.
    const base = `${siteOrigin}/${pageRel.replace(/index\.html$/, '')}`
    try {
      url = new URL(raw, base)
    } catch {
      return { kind: 'malformed' }
    }
  }

  return {
    kind: 'internal',
    samePage: false,
    pathname: safeDecode(url.pathname),
    fragment: safeDecode(url.hash.replace(/^#/, '')),
  }
}

/** `decodeURIComponent` throws on a stray `%`; a malformed escape is still a link. */
function safeDecode(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

export async function runLinkCheck(dir, options = {}) {
  const siteOrigin = options.siteOrigin ?? (await readSiteOrigin())
  const pages = loadPages(dir)
  const failures = []
  const counts = { pages: pages.length, internal: 0, fragments: 0, external: 0, ignored: 0 }

  if (pages.length === 0) {
    return { failures: [emptyScanFailure(dir)], counts, siteOrigin }
  }

  const files = fileIndex(dir)

  // Two passes: every page's anchors must be known before any page's fragments
  // are resolved, because a cross-page fragment can point backwards.
  const anchorsByPage = new Map()
  const referencesByPage = new Map()
  for (const page of pages) {
    const document = parseHtml(page.html)
    anchorsByPage.set(page.rel, collectAnchors(document))
    referencesByPage.set(page.rel, collectReferences(document))
  }

  for (const page of pages) {
    for (const ref of referencesByPage.get(page.rel)) {
      const found = classify(ref.value, { pageRel: page.rel, siteOrigin })
      const shown = `<${ref.tag} ${ref.attr}="${ref.value}">`

      if (found.kind === 'external') {
        counts.external++
        continue
      }
      if (found.kind === 'ignored') {
        counts.ignored++
        continue
      }
      if (found.kind === 'empty') {
        failures.push({
          where: page.rel,
          what: shown,
          why: `Empty ${ref.attr}. It resolves to the current page, which is never what was meant.`,
        })
        continue
      }
      if (found.kind === 'malformed') {
        failures.push({ where: page.rel, what: shown, why: 'Not a parseable URL.' })
        continue
      }

      let target = page.rel
      if (!found.samePage) {
        counts.internal++

        // `trailingSlash: 'never'` in astro.config.mjs and `"trailingSlash": false`
        // in vercel.json both mean `/faq/` is served as a 308 to `/faq`. A link
        // that costs a redirect is an authoring mistake with a one-character fix.
        if (found.pathname !== '/' && found.pathname.endsWith('/')) {
          failures.push({
            where: page.rel,
            what: shown,
            why:
              `Trailing slash on "${found.pathname}". astro.config.mjs sets ` +
              "trailingSlash: 'never', so this is served as a redirect. Drop the slash.",
          })
          continue
        }

        const resolved = resolveRoute(found.pathname, files)
        if (!resolved.ok) {
          failures.push({
            where: page.rel,
            what: shown,
            why: `No file in the build serves "${found.pathname}". Tried: ${resolved.tried.join(', ')}.`,
          })
          continue
        }
        target = resolved.target
      }

      // `#top` is a document-top anchor every browser honours without an element.
      if (found.fragment === '' || found.fragment === 'top') continue
      counts.fragments++

      if (!target.endsWith('.html')) {
        failures.push({
          where: page.rel,
          what: shown,
          why: `"${target}" is not an HTML page, so "#${found.fragment}" cannot resolve.`,
        })
        continue
      }

      const anchors = anchorsByPage.get(target)
      if (!anchors) {
        failures.push({
          where: page.rel,
          what: shown,
          why: `"${target}" was not scanned, so "#${found.fragment}" cannot be verified.`,
        })
        continue
      }
      if (!anchors.has(found.fragment)) {
        failures.push({
          where: page.rel,
          what: shown,
          why:
            `No element in ${found.samePage ? 'this page' : target} has id="${found.fragment}" ` +
            '(or a legacy <a name>). Heading anchors are an external contract — the in-app ' +
            'Extension Manager deep-links into /extensions-policy — so a reworded heading is a ' +
            'broken link somewhere this checker cannot see.',
        })
      }
    }
  }

  return { failures, counts, siteOrigin }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts, siteOrigin } = await runLinkCheck(dir)
  process.exit(
    report(
      'check-links',
      [
        ['pages', counts.pages],
        ['internal links checked', counts.internal],
        ['fragments checked', counts.fragments],
        [`external skipped (internal origin is ${siteOrigin})`, counts.external],
        ['non-http skipped', counts.ignored],
      ],
      failures,
    ),
  )
}

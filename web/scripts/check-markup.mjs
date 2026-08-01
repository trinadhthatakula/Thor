/**
 * Cheap structural assertions over the built HTML, run **inside the build**.
 *
 * ## Why the build chain and not CI
 *
 * The headline rule here is the no-italics rule. `Type.kt` maps every italic
 * style to the same font file as its upright, so Thor has no real italic and the
 * site must not invent one. The content drafts carry 334 asterisk runs against
 * that rule, which makes an accidental `<em>` the single likeliest content
 * regression this site will ever have.
 *
 * The web CI workflow is path-filtered and deliberately not a required check —
 * it has to be, because a required check that path-skips leaves a PR pending
 * forever. That makes CI structurally unable to guard the deploy path. So this
 * runs in `npm run build`, where Vercel executes it and a failure blocks the bad
 * deploy instead of annotating a PR nobody has to merge.
 *
 * ## What else is here
 *
 * Only assertions that are cheap, deterministic, and wrong-in-production if
 * violated: one `<h1>` per page, no skipped heading level, `alt` on every
 * `<img>`, and `lang` on `<html>`. Anything needing layout or a real CSSOM
 * belongs in `check-a11y.mjs`, which is CI-only for exactly that reason.
 */
import { readFileSync } from 'node:fs'
import { parseHtml } from './lib/dom.mjs'
import { loadPages, relPosix, walkFiles } from './lib/dist.mjs'
import { dirArg, emptyScanFailure, isMain, report } from './lib/report.mjs'

/**
 * `<i>` is checked as well as `<em>`. Icon fonts are the usual excuse for a bare
 * `<i>`, and this site has none — every icon is inline SVG — so an `<i>` here
 * can only be italic text or a component someone copied in from elsewhere.
 */
const ITALIC_TAGS = ['em', 'i']

export function checkPage(rel, html) {
  const document = parseHtml(html)
  const failures = []
  const at = (what, why) => failures.push({ where: rel, what, why })

  for (const tag of ITALIC_TAGS) {
    for (const el of document.querySelectorAll(tag)) {
      at(
        `<${tag}> element: "${(el.textContent ?? '').trim().slice(0, 80)}"`,
        'The site has a hard no-italics rule: Type.kt points every italic style at the same ' +
          'font file as its upright, so Thor has no real italic and the site must not invent ' +
          'one. In MDX this is almost always a single-asterisk run that should have been bold, ' +
          'a callout, or nothing.',
      )
    }
  }

  const h1s = document.querySelectorAll('h1')
  if (h1s.length !== 1) {
    at(
      `${h1s.length} <h1> elements`,
      'Every page needs exactly one h1: it is the document title for screen readers and the ' +
        'first thing a search result shows. Zero means the layout swallowed it; more than one ' +
        'means a content heading was written at the wrong level.',
    )
  }

  let previous = 0
  for (const heading of document.querySelectorAll('h1, h2, h3, h4, h5, h6')) {
    const level = Number(heading.tagName.slice(1))
    if (previous !== 0 && level > previous + 1) {
      at(
        `heading level jumps h${previous} -> h${level}: "${(heading.textContent ?? '').trim().slice(0, 60)}"`,
        'A skipped level breaks the outline a screen-reader user navigates by. Use the next ' +
          'level down and style it, rather than picking a heading tag for its size.',
      )
    }
    previous = level
  }

  for (const img of document.querySelectorAll('img')) {
    if (img.hasAttribute('alt')) continue
    at(
      `<img> with no alt: src="${img.getAttribute('src') ?? ''}"`,
      'Every img needs an alt attribute. alt="" is the correct answer for a decorative image ' +
        'and is accepted here; a missing attribute makes a screen reader read the filename.',
    )
  }

  const lang = document.documentElement?.getAttribute('lang')
  if (!lang) {
    at(
      '<html> has no lang attribute',
      'Without lang, a screen reader pronounces the page with whatever voice it happens to ' +
        'have loaded. The site is English-only at launch, so this is lang="en".',
    )
  }

  return failures
}

/** `--name:` — a custom property being declared, in CSS or in an inline style. */
const DECLARED = /(--[a-z0-9-]+)\s*:/gi
/** `var(--name)` with no fallback. With a fallback there is nothing to fail to. */
const REFERENCED = /var\(\s*(--[a-z0-9-]+)\s*\)/gi

const matchAll = (text, pattern) => [...text.matchAll(pattern)].map((m) => m[1].toLowerCase())

/**
 * Every `var(--x)` in the built output resolves to something.
 *
 * `token-usage.test.ts` makes this assertion over `src/`, which cannot see a
 * custom property that only exists after a build. Shiki's `css-variables` theme
 * is exactly that case: it writes `color:var(--astro-code-token-function)` inline
 * onto every highlighted span, Astro declares those names nowhere outside its dev
 * error overlay, and it emits them without a fallback. 121 references, none of
 * them resolvable, so every code sample on the site rendered in one flat inherited
 * colour — and no gate could see it, because an undeclared custom property is not
 * a CSS error. It computes to `unset` and the page renders.
 *
 * Whole-build rather than per-page: a property declared in the stylesheet and used
 * in an inline style is correct, and only a check that has read both can tell.
 */
export function checkCustomProperties(dir) {
  const failures = []
  const declared = new Set()
  const references = new Map()

  for (const file of walkFiles(dir)) {
    const isCss = file.endsWith('.css')
    if (!isCss && !file.endsWith('.html')) continue
    const rel = relPosix(dir, file)
    const text = readFileSync(file, 'utf8')

    // In HTML, only `<style>` blocks and `style="…"` attributes are CSS. Scanning
    // the whole document would read prose about a token as a declaration of it —
    // and this site's pages quote token names in running text.
    //
    // `</style >` with whitespace before the `>` is valid HTML, so the end tag
    // takes `\s*`. Astro's compressor does not emit that form today, but a missed
    // <style> block here means every property it declares reads as undeclared,
    // and the gate reports a failure for correct CSS.
    const css = isCss
      ? [text]
      : [
          ...[...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style\s*>/gi)].map((m) => m[1]),
          ...[...text.matchAll(/style="([^"]*)"/gi)].map((m) => m[1]),
        ]

    for (const chunk of css) {
      for (const name of matchAll(chunk, DECLARED)) declared.add(name)
      for (const name of matchAll(chunk, REFERENCED)) {
        if (!references.has(name)) references.set(name, rel)
      }
    }
  }

  for (const [name, rel] of [...references].sort()) {
    if (declared.has(name)) continue
    failures.push({
      where: rel,
      what: `var(${name}) is never declared in the build`,
      why:
        'An undeclared custom property is invalid at computed-value time, so the declaration ' +
        'using it falls back to `unset` — inherited for colour, initial otherwise. Nothing ' +
        'errors and the page renders, which is why this needs a gate. If it came from an ' +
        'integration rather than from src/, declare it in tokens.css: Shiki\'s css-variables ' +
        'theme emits --astro-code-* this way.',
    })
  }

  return { failures, counts: { declared: declared.size, referenced: references.size } }
}

export function runMarkupCheck(dir) {
  const pages = loadPages(dir)
  const counts = { pages: pages.length, properties: 0 }
  if (pages.length === 0) {
    return { failures: [emptyScanFailure(dir)], counts }
  }
  const properties = checkCustomProperties(dir)
  counts.properties = properties.counts.referenced
  const failures = [
    ...pages.flatMap((page) => checkPage(page.rel, page.html)),
    ...properties.failures,
  ]
  return { failures, counts }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts } = runMarkupCheck(dir)
  process.exit(
    report(
      'check-markup',
      [
        ['pages checked', counts.pages],
        ['custom properties resolved', counts.properties],
      ],
      failures,
    ),
  )
}

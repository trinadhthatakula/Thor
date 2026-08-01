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
import { parseHtml } from './lib/dom.mjs'
import { loadPages } from './lib/dist.mjs'
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

export function runMarkupCheck(dir) {
  const pages = loadPages(dir)
  const counts = { pages: pages.length }
  if (pages.length === 0) {
    return { failures: [emptyScanFailure(dir)], counts }
  }
  const failures = pages.flatMap((page) => checkPage(page.rel, page.html))
  return { failures, counts }
}

if (isMain(import.meta.url)) {
  const dir = dirArg()
  const { failures, counts } = runMarkupCheck(dir)
  process.exit(report('check-markup', [['pages checked', counts.pages]], failures))
}

/**
 * One HTML parser for every checker in this directory.
 *
 * jsdom rather than a regex scanner, deliberately. The claims gate's whole value
 * rests on extracting the *rendered text* of a page, and that is exactly where a
 * hand-rolled scanner is weakest: entities, `<script>` bodies, attribute values
 * that look like tags. A parser bug there does not produce a loud failure — it
 * produces an empty string and a permanently green build, which is the failure
 * mode this phase exists to prevent. jsdom is already a devDependency (the a11y
 * checker needs it), Vercel installs devDependencies during a build, and eight
 * static pages cost milliseconds.
 *
 * `runScripts` is left at its default, so nothing in `dist` executes. The theme
 * toggle is the only script on the site and running it would only change
 * attributes we do not inspect.
 */
import { JSDOM } from 'jsdom'

/** Elements whose text is not part of the page's prose. */
const NON_PROSE = new Set(['script', 'style', 'noscript', 'template', 'svg', 'head'])

/**
 * Elements that flow inside a line. Everything else gets a newline on each side
 * when text is extracted, so `<p>one</p><p>two</p>` cannot read as "onetwo" —
 * which it otherwise would, because Astro compresses HTML by default and there
 * is no whitespace between those tags in the built output.
 */
const INLINE = new Set([
  'a', 'abbr', 'b', 'bdi', 'bdo', 'button', 'cite', 'code', 'data', 'dfn', 'em',
  'i', 'kbd', 'label', 'mark', 'q', 'rp', 'rt', 'ruby', 's', 'samp', 'small',
  'span', 'strong', 'sub', 'sup', 'time', 'u', 'var', 'wbr',
])

export function parseHtml(html) {
  return new JSDOM(html).window.document
}

/**
 * The page's visible prose, with block elements separated by newlines.
 *
 * `alt` text is included: a claim is no less wrong for being in an alt
 * attribute, and alt text is read aloud to exactly the users least able to
 * cross-check it. `title` is not, because Astro puts the page title in `<head>`
 * and a `<title>` is not prose the reader can act on.
 */
export function extractText(document) {
  const parts = []

  const visit = (node, preformatted) => {
    if (node.nodeType === 3) {
      // Collapse whitespace *inside* a text node, so the only newlines in the
      // output are the block markers pushed below.
      //
      // Prose here is hard-wrapped at about a hundred columns and that wrapping
      // survives into `dist` as a newline in the middle of a paragraph's text
      // node. `toSentences` treats every newline as a sentence boundary, so a
      // claim that happened to straddle a source line break was split in half
      // and no pattern spanning the break could ever match it — the rule went
      // quietly unenforced on that sentence and the build stayed green. Which
      // claims escaped was decided by where the author's editor wrapped.
      parts.push(preformatted ? node.nodeValue : node.nodeValue.replace(/\s+/g, ' '))
      return
    }
    if (node.nodeType !== 1) return

    const tag = node.tagName.toLowerCase()
    if (NON_PROSE.has(tag)) return

    if (tag === 'br') {
      parts.push('\n')
      return
    }
    if (tag === 'img') {
      const alt = node.getAttribute('alt')
      if (alt) parts.push(`\n${alt}\n`)
      return
    }

    const block = !INLINE.has(tag)
    if (block) parts.push('\n')
    // `pre` keeps its newlines: they are the code's own line structure, not an
    // artefact of how the prose was wrapped, and joining two lines of a snippet
    // into one would invent text that appears nowhere on the page.
    for (const child of node.childNodes) visit(child, preformatted || tag === 'pre')
    if (block) parts.push('\n')
  }

  visit(document.body ?? document.documentElement, false)
  return parts.join('')
}

/**
 * Every fragment target on the page: `id` on any element, plus the legacy
 * `<a name>` anchor, which browsers still resolve and which hand-written HTML
 * in a content page can still produce.
 */
export function collectAnchors(document) {
  const anchors = new Set()
  for (const el of document.querySelectorAll('[id]')) {
    const id = el.getAttribute('id')
    if (id) anchors.add(id)
  }
  for (const el of document.querySelectorAll('a[name]')) {
    const name = el.getAttribute('name')
    if (name) anchors.add(name)
  }
  return anchors
}

/**
 * Attributes that point at another resource, as `{ tag, attr, value }`.
 *
 * `src` is included alongside `href`. A broken `src` ships a missing image or a
 * dead script just as surely as a broken `href` ships a 404, and it costs
 * nothing to resolve both through the same index. `srcset` is not parsed:
 * Astro's `<Image>` generates it from an import that already failed the build if
 * the file was missing.
 */
export function collectReferences(document) {
  const refs = []
  for (const el of document.querySelectorAll('[href], [src]')) {
    const tag = el.tagName.toLowerCase()
    for (const attr of ['href', 'src']) {
      if (!el.hasAttribute(attr)) continue
      refs.push({ tag, attr, value: el.getAttribute(attr) ?? '' })
    }
  }
  return refs
}

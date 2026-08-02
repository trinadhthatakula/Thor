/**
 * The shared helpers, tested on their own.
 *
 * These are small enough to look obviously correct and are the reason the three
 * build gates either work or silently do not. Two in particular:
 *
 *  - `normalise` is the only thing standing between a rule written with straight
 *    quotes and a smartypants-rendered page it can never match.
 *  - `globToRegExp` decides which pages a rule applies to, and a glob that
 *    matches nothing turns its rule off without saying so.
 */
import { describe, expect, it } from 'vitest'
import { flatten, normalise, toSentences } from './lib/text.mjs'
import { extractText, parseHtml } from './lib/dom.mjs'
import { globToRegExp, matchGlob, relPosix } from './lib/dist.mjs'

describe('normalise', () => {
  it('maps every curly form smartypants emits onto its ASCII original', () => {
    // The full set in one string, because a character class that has quietly
    // stopped covering one of these is unreadable in a diff.
    expect(normalise('‘a’ “b” – — … don’t')).toBe(
      "'a' \"b\" - - ... don't",
    )
  })

  it('is what makes a straight-quote pattern match curly output', () => {
    const pattern = /\bdon't install\b/i
    expect(pattern.test('please don’t install it')).toBe(false)
    expect(pattern.test(normalise('please don’t install it'))).toBe(true)
  })

  it('turns a non-breaking space into a plain one', () => {
    // `API 37` is what a typographer's non-breaking space does to a fact,
    // and `\bapi\s*37\b` would still match it — but `API 37` as a literal would
    // not, and half the rules are written that way.
    expect(normalise('API 37')).toBe('API 37')
  })

  it('deletes zero-width characters rather than turning them into spaces', () => {
    expect(normalise('fact​ory state')).toBe('factory state')
  })

  it('collapses runs of spaces but keeps block boundaries', () => {
    expect(normalise('one   two\n\n\nthree')).toBe('one two\nthree')
  })

  it('flatten removes the block boundaries for whole-page matching', () => {
    expect(flatten('one\ntwo')).toBe('one two')
  })
})

describe('toSentences', () => {
  it('splits on terminators followed by a new sentence', () => {
    expect(toSentences('One thing. Another thing! A third?')).toEqual([
      'One thing.',
      'Another thing!',
      'A third?',
    ])
  })

  it('keeps version strings and dotted identifiers in one piece', () => {
    // Three rules match on exactly these tokens. A splitter that shattered them
    // would leave those rules unable to fire on the copy that contains them.
    const text = 'Tag v1.81.9-dev-82 was a full release. It reads thor.extension.class only.'
    expect(toSentences(text)).toEqual([
      'Tag v1.81.9-dev-82 was a full release.',
      'It reads thor.extension.class only.',
    ])
  })

  it('treats a block boundary as a sentence boundary', () => {
    // Astro compresses HTML, so two adjacent paragraphs have no whitespace
    // between them; without this they read as one run-on sentence and a
    // `[\s\S]{0,160}` window would span two unrelated claims.
    expect(toSentences('no punctuation here\nand a second paragraph')).toEqual([
      'no punctuation here',
      'and a second paragraph',
    ])
  })
})

describe('extractText', () => {
  const text = (html) => extractText(parseHtml(html))

  it('does not turn a hard-wrapped paragraph into two sentences', () => {
    // The prose on this site is wrapped at about a hundred columns and that
    // wrapping reaches `dist` as a newline inside the paragraph's text node.
    // `toSentences` breaks on every newline, so a claim straddling the wrap was
    // split in half and no pattern spanning the break could match it. Which
    // claims escaped the gate was decided by where the editor wrapped, which is
    // the worst possible way for a correctness gate to choose what to enforce.
    const html = '<p>Thor\'s own code makes exactly one kind\nof network request, when you ask.</p>'
    expect(toSentences(text(html))).toEqual([
      "Thor's own code makes exactly one kind of network request, when you ask.",
    ])
  })

  it('still separates adjacent blocks', () => {
    expect(toSentences(text('<p>one</p><p>two</p>'))).toEqual(['one', 'two'])
  })

  it('keeps the line structure of a code block', () => {
    // A snippet's newlines are the code's own, not an artefact of prose
    // wrapping. Joining them would invent a line that appears nowhere.
    expect(toSentences(text('<pre><code>first line\nsecond line</code></pre>'))).toEqual([
      'first line',
      'second line',
    ])
  })

  it('reads the description meta tags, which no reader sees on the page', () => {
    // For as long as this walk started at `document.body`, a claim written into
    // a description was unreachable by every rule in claims.mjs — and that is
    // the sentence Google prints under the search result and Telegram prints in
    // the unfurl card, so it is read by more people than the page body is.
    const html =
      '<html><head><meta name="description" content="A claim in the description."></head>' +
      '<body><p>Body text.</p></body></html>'
    expect(toSentences(text(html))).toEqual(['A claim in the description.', 'Body text.'])
  })

  it('checks a differing og:description separately but does not repeat a copy of it', () => {
    // og:description is normally a duplicate of description, and reporting one
    // authoring mistake three times buries every other finding. Two that differ
    // are two claims, and both have to be checked.
    const meta = (attr, value, content) =>
      `<meta ${attr}="${value}" content="${content}">`
    const html =
      '<html><head>' +
      meta('name', 'description', 'Shared sentence.') +
      meta('property', 'og:description', 'Shared sentence.') +
      meta('name', 'twitter:description', 'A different sentence.') +
      '</head><body><p>Body.</p></body></html>'
    expect(toSentences(text(html))).toEqual(['Shared sentence.', 'A different sentence.', 'Body.'])
  })

  it('leaves the title alone', () => {
    // A title is a label, not a claim the reader can act on, and a rule that
    // matched a five-word title would fire on the nav too.
    const html = '<html><head><title>Thor</title></head><body><p>Body.</p></body></html>'
    expect(toSentences(text(html))).toEqual(['Body.'])
  })
})

describe('globToRegExp', () => {
  it('matches a single segment with *', () => {
    expect(matchGlob('*.html', 'index.html')).toBe(true)
    expect(matchGlob('*.html', 'faq/index.html')).toBe(false)
  })

  it('crosses segments with ** and allows zero of them', () => {
    expect(matchGlob('**/*.html', 'index.html')).toBe(true)
    expect(matchGlob('**/*.html', 'a/b/index.html')).toBe(true)
  })

  it('matches everything with a bare **, which is what every live rule uses', () => {
    expect(matchGlob('**', 'index.html')).toBe(true)
    expect(matchGlob('**', 'build-an-extension/index.html')).toBe(true)
  })

  it('supports {a,b} alternation', () => {
    expect(matchGlob('{faq,privacy}/index.html', 'privacy/index.html')).toBe(true)
    expect(matchGlob('{faq,privacy}/index.html', 'download/index.html')).toBe(false)
  })

  it('treats a dot as a literal, not as any-character', () => {
    expect(matchGlob('index.html', 'indexXhtml')).toBe(false)
  })

  it('refuses a nested brace rather than matching something unintended', () => {
    expect(() => globToRegExp('{a,{b,c}}')).toThrow(/Nested/)
    expect(() => globToRegExp('{a,b')).toThrow(/Unclosed/)
  })
})

describe('relPosix', () => {
  it('produces forward slashes so a glob written on macOS works on Windows CI', () => {
    expect(relPosix('/dist', '/dist/faq/index.html')).toBe('faq/index.html')
  })
})

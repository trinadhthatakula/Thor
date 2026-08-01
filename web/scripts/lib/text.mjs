/**
 * Turning built HTML into text a regex can be trusted against.
 *
 * The single largest way a claims gate dies quietly: MDX runs smartypants by
 * default, so `don't` in the source is a curly apostrophe in `dist`, `"quoted"`
 * becomes curly doubles, and `--` becomes an en dash. A rule author writes the
 * regex with the straight characters on their keyboard, the build goes green,
 * and the rule never matches anything again — for the life of the site. Every
 * pattern in `src/content/claims.mjs` is therefore written against *normalised*
 * text, and normalisation happens here, once.
 */

/**
 * Curly quotes, dashes, ellipses and exotic spaces, mapped to their ASCII
 * originals. Written as `\u` escapes on purpose — several of these are invisible
 * or indistinguishable in an editor, and a character class you cannot proofread
 * is one that quietly stops covering one of them.
 */
const TYPOGRAPHY = [
  // U+2018..U+201B curly singles, U+2032 prime, U+02BC modifier apostrophe.
  [/[‘’‚‛′ʼ]/g, "'"],
  // U+201C..U+201F curly doubles, U+2033 double prime, guillemets.
  [/[“”„‟″«»]/g, '"'],
  // U+2010..U+2015 dashes, U+2212 minus.
  [/[‐-―−]/g, '-'],
  [/…/g, '...'],
  // NBSP, en/em/figure/punctuation/thin/hair spaces, narrow NBSP, word joiner,
  // ideographic space.
  [/[  -   ⁠　]/g, ' '],
  // Zero-width space, ZWNJ, ZWJ, BOM, soft hyphen. Invisible, so they must be
  // deleted rather than turned into a space that would break a phrase in half.
  [/[​-‍﻿­]/g, ''],
]

/**
 * Normalise typography and collapse whitespace runs, but keep newlines: block
 * boundaries survive as `\n` so {@link toSentences} can treat them as sentence
 * breaks. Unicode is composed first, so a decomposed accented letter is one
 * character.
 */
export function normalise(text) {
  let out = text.normalize('NFC')
  for (const [pattern, replacement] of TYPOGRAPHY) out = out.replace(pattern, replacement)
  return out
    .replace(/[^\S\n]+/g, ' ')
    .replace(/ *\n+ */g, '\n')
    .trim()
}

/** Normalised text with newlines flattened too — for whole-page `require` matching. */
export function flatten(text) {
  return normalise(text).replace(/\n/g, ' ')
}

/**
 * Split normalised text into sentences.
 *
 * Terminators only count when whitespace and an opening character follow, so
 * `v1.81.9-dev-82`, `3.0.0` and `thor.extension.class` stay in one piece. That
 * matters: three of the claims rules match on exactly those tokens, and a
 * splitter that shattered them would leave those rules unable to fire.
 *
 * Block boundaries (`\n`) are always sentence boundaries — Astro compresses HTML
 * by default, so two adjacent paragraphs have no whitespace between them and
 * would otherwise read as one run-on sentence.
 */
export function toSentences(text) {
  return normalise(text)
    .split('\n')
    .flatMap((block) => block.split(/(?<=[.!?])["')\]]*\s+(?=["'([]*[A-Z0-9])/))
    .map((s) => s.trim())
    .filter(Boolean)
}

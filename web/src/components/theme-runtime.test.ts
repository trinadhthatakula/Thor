/**
 * The contract between the site's only two scripts.
 *
 * ThemeScript.astro runs once, blocking, before the first paint; ThemeToggle.astro
 * runs after and owns every change from then on. Nothing links them but a comment,
 * and the comment was already right when the code went wrong: ThemeScript spends a
 * paragraph on the one operation a toggle must never perform — dropping the
 * `data-theme` attribute to mean "system" — and ThemeToggle's `paint` opened with
 * exactly that line, with its own header comment asserting the opposite.
 *
 * It is invisible in review because it looks correct: `:root` plus the
 * `prefers-color-scheme` query resolve the same colours with the attribute absent.
 * Only AMOLED breaks, because tokens.css keys it on
 * `[data-theme='dark'][data-amoled='true']`, which cannot match without a concrete
 * `data-theme` — so the toggle switched off, one frame later, what the pre-paint
 * script had just switched on.
 *
 * These are source assertions rather than DOM ones. Both scripts are `is:inline`
 * strings that never run in vitest, and the properties worth pinning are about
 * which operations appear at all — which is a question about the text.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const read = (name: string) =>
  readFileSync(fileURLToPath(new URL(`./${name}.astro`, import.meta.url)), 'utf8')

const themeScript = read('ThemeScript')
const themeToggle = read('ThemeToggle')

/**
 * Only the `<script>` body, so a prohibition quoted in a comment is not a violation.
 *
 * Case-insensitive because a tag filter that only matches lower case is the wrong
 * shape even where it happens to be safe. Here it would have failed loudly — the
 * assertion below demands exactly one match, so `<SCRIPT>` would have thrown
 * rather than quietly waved a violation through — but the habit is what CodeQL's
 * js/bad-tag-filter is about, and the fix is one flag.
 */
function scriptBody(source: string): string {
  const blocks = [...source.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1])
  expect(blocks.length, 'expected exactly one inline script').toBe(1)
  return blocks[0].replace(/\/\/[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '')
}

describe('theme runtime', () => {
  it('never removes data-theme, in either script', () => {
    for (const [name, source] of [
      ['ThemeScript', themeScript],
      ['ThemeToggle', themeToggle],
    ] as const) {
      expect(
        scriptBody(source),
        `${name} drops a theme attribute; "system" is thor-theme absent from storage, ` +
          'not data-theme absent from the DOM, or AMOLED can never match',
      ).not.toMatch(/removeAttribute\(\s*['"]data-(theme|amoled)['"]/)
    }
  })

  it('keeps following the OS while in system mode', () => {
    // Setting a concrete data-theme is what takes the live CSS media query out of
    // the picture, so the toggle has to do that job instead. Without this the page
    // would be frozen at whatever the OS said when it loaded — a regression the
    // attribute-removing version did not have, and the reason the two halves of
    // this fix cannot be split.
    const body = scriptBody(themeToggle)
    expect(body).toMatch(/matchMedia\(/)
    expect(body, 'no listener for an OS theme change').toMatch(
      /add(EventListener|Listener)\(\s*['"]?change/,
    )
  })

  it('asks both scripts the same media query, so a no-preference machine agrees', () => {
    // `no-preference` was dropped from Media Queries Level 5: a machine with no
    // preference answers `false` to a dark query and `true` to a light one. Two
    // scripts asking opposite questions would therefore disagree on exactly those
    // machines — pre-paint dark, post-paint light, i.e. a flash on load.
    for (const source of [themeScript, themeToggle]) {
      expect(scriptBody(source)).toContain("matchMedia('(prefers-color-scheme: light)')")
    }
  })

  it('keeps both scripts inline and blocking', () => {
    // Astro emits `type="module"` without is:inline, and a module script is
    // deferred by definition, so it would always lose the race with first paint.
    for (const [name, source] of [
      ['ThemeScript', themeScript],
      ['ThemeToggle', themeToggle],
    ] as const) {
      expect(source, `${name} must not be bundled`).toMatch(/<script is:inline>/)
    }
  })

  it('pins AMOLED to dark in both scripts', () => {
    // tokens.css only defines the AMOLED palette under dark. A 'true' left set
    // while the page is light is invisible until the visitor cycles to dark, at
    // which point they get black from a preference they never expressed here.
    for (const [name, source] of [
      ['ThemeScript', themeScript],
      ['ThemeToggle', themeToggle],
    ] as const) {
      expect(scriptBody(source), `${name} sets data-amoled without checking dark`).toMatch(
        /theme === 'dark' && amoled === 'true'/,
      )
    }
  })
})

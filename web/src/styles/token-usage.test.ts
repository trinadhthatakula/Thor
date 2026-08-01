/**
 * Asserts that every `var(--x)` on the site names a custom property something
 * actually declares.
 *
 * This is the CSS twin of `src/components/props-detection.test.ts`, and it exists
 * for the same reason: the failure is *silent*, in every tool we own.
 *
 * A `var()` whose custom property has no value and no fallback does not fall back
 * to "the previous declaration" and does not log anything. Per CSS Custom
 * Properties Level 1 §3, the whole declaration becomes **invalid at computed-value
 * time**, which is defined as `unset` — `inherit` for an inherited property,
 * `initial` for a non-inherited one. So a component written against a token that
 * does not exist ships like this:
 *
 *     padding: var(--space-4)      ->  padding: 0
 *     border-radius: var(--radius-md)  ->  border-radius: 0
 *     font-size: var(--text-sm)    ->  whatever the parent's font-size is
 *
 * Nothing catches it. `astro check` type-checks TypeScript and template
 * expressions, not stylesheets. Astro's build treats a `<style>` block as opaque
 * CSS and never resolves a custom property, because resolution is a *runtime*,
 * per-element operation. The browser prints no console warning — an unresolvable
 * `var()` is well-formed CSS, not a parse error. The page just quietly loses its
 * spacing and looks "a bit off" to whoever opens it next.
 *
 * The site shipped in exactly that state: over a hundred references to a
 * `--space-N` / `--text-N` scale that tokens.css never declared (it uses the t-shirt
 * scale, `--space-s`), every one of them dropped, none of them reported.
 *
 * ── What is deliberately NOT an offence ───────────────────────────────────────
 *
 * `var(--x, 1rem)` with a fallback is well-defined even when `--x` is undefined;
 * it is the standard progressive-enhancement idiom. Flagging it would punish
 * correct code, so a reference with a fallback is exempt — that is the *only*
 * exemption, and it is the escape hatch for a genuinely optional property.
 *
 * `var(${role})`, as used by the styleguide page to render the type scale, names
 * its property at runtime and cannot be resolved by reading the source. Those are
 * skipped rather than guessed at; the tokens they interpolate are literals in that
 * page's frontmatter and are covered by contrast.test.ts.
 */
import { readFileSync, readdirSync } from 'node:fs'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const SRC = fileURLToPath(new URL('..', import.meta.url))

/**
 * The stylesheets whose declarations are visible site-wide.
 *
 * base.css `@import`s tokens.css, and every layout imports base.css, so a property
 * declared in either is in scope on every page. Nothing else qualifies: a property
 * declared in a component's scoped `<style>` is only reliably in scope inside that
 * component, which is why the walk below adds each file's own declarations to its
 * own lookup and to nobody else's.
 */
const GLOBAL_STYLESHEETS = ['tokens.css', 'base.css'] as const

/* ── Extraction ────────────────────────────────────────────────────────────── */

export interface VarReference {
  readonly name: string
  /** 1-indexed line in the *original* file, not in the masked CSS. */
  readonly line: number
  readonly hasFallback: boolean
}

export interface Offender {
  /** `src/components/Callout.astro:36` — greppable, clickable in a terminal. */
  readonly where: string
  readonly name: string
}

/**
 * Iterate a global regex without sharing `lastIndex` between calls.
 *
 * A module-level `/g` regex is stateful, and a second `exec` loop over it starts
 * wherever the first stopped — which in a gate shows up as "some files mysteriously
 * have no matches". Cloning per call makes each scan independent.
 */
function* matches(re: RegExp, text: string): Generator<RegExpExecArray> {
  const scanner = new RegExp(re.source, re.flags.includes('g') ? re.flags : `${re.flags}g`)
  let match: RegExpExecArray | null
  while ((match = scanner.exec(text)) !== null) {
    // A pattern that can match empty would spin forever otherwise.
    if (match[0] === '') scanner.lastIndex++
    yield match
  }
}

/** Same length, same newlines, no content — so offsets keep pointing at real lines. */
const blank = (text: string): string => text.replace(/[^\n]/g, ' ')

/**
 * Reduce a source file to just the parts a browser parses as CSS, **without moving
 * anything**.
 *
 * Non-CSS spans are overwritten with spaces rather than removed. Concatenating the
 * `<style>` blocks instead would be simpler and would make every reported line
 * number a lie, which for a gate whose entire value is "it tells you where" is the
 * same as not reporting at all.
 *
 * Masking is what keeps the two classic false positives out:
 *   - an `.astro` frontmatter or `<script>` that mentions a token in a string
 *   - an `.mdx` fenced code block showing CSS as documentation
 * and comment stripping is what keeps out the third — prose *inside* a `<style>`
 * block. `base.css` and `ThemeToggle.astro` both carry comments of the form
 * "`--outline`, not `--outline-variant`: …", which a naive declaration scan reads
 * as a declaration and which would then whitelist an undeclared token.
 */
export function cssRegions(source: string, extension: string): string {
  let text = source

  if (extension !== '.css') {
    const keep: Array<readonly [number, number]> = []

    // `<style>`, `<style is:global>`, `<style define:vars={…}>` — body only.
    // Case-insensitive, and `\s*` before the closing `>`: `</style >` is valid and
    // a missed block is not a loud failure here, it is a stylesheet this gate
    // silently stops reading.
    for (const block of matches(/<style[^>]*>([\s\S]*?)<\/style\s*>/gi, source)) {
      const open = block.index + block[0].indexOf('>') + 1
      keep.push([open, open + block[1].length])
    }

    // Inline `style` attributes. None of the site's components use one today, but a
    // gate that only watches `<style>` blocks stops working the moment one does.
    for (const attr of matches(/\bstyle\s*=\s*(?:"[^"]*"|'[^']*'|\{`[^`]*`\}|`[^`]*`)/g, source)) {
      keep.push([attr.index, attr.index + attr[0].length])
    }

    const masked = blank(source).split('')
    for (const [from, to] of keep) {
      for (let i = from; i < to; i++) masked[i] = source[i]
    }
    text = masked.join('')
  }

  return text.replace(/\/\*[\s\S]*?\*\//g, blank)
}

/**
 * Custom properties in *declaration* position: `--name:` preceded by a block start,
 * a semicolon or whitespace.
 *
 * The lead-in is what separates a declaration from a reference. `var(--name)` is
 * never followed by a colon, so it cannot match here; `--a: var(--b)` yields `--a`
 * and only `--a`.
 */
export function declaredProperties(css: string): Set<string> {
  const found = new Set<string>()
  for (const match of matches(/(?:^|[;{}\s])(--[A-Za-z0-9_-]+)\s*:/g, css)) found.add(match[1])
  return found
}

/** Every `var(--name…)`, with its line and whether a fallback follows the comma. */
export function varReferences(css: string): VarReference[] {
  const refs: VarReference[] = []
  for (const match of matches(/var\(\s*(--[A-Za-z0-9_-]+)\s*(,|\))/g, css)) {
    refs.push({ name: match[1], line: lineOf(css, match.index), hasFallback: match[2] === ',' })
  }
  return refs
}

function lineOf(text: string, index: number): number {
  let line = 1
  for (let i = 0; i < index; i++) if (text[i] === '\n') line++
  return line
}

/**
 * The gate itself, as one function, so the must-fail fixture at the bottom runs the
 * production code path rather than a second implementation of it that can agree
 * with the first while both are wrong.
 */
export function offendersIn(
  label: string,
  source: string,
  extension: string,
  globalProperties: ReadonlySet<string>,
): Offender[] {
  const css = cssRegions(source, extension)
  // A component may legitimately declare its own `--x` in its own `<style>` block
  // and use it two lines later. That is local, and it counts.
  const local = declaredProperties(css)
  return varReferences(css)
    .filter((ref) => !ref.hasFallback)
    .filter((ref) => !globalProperties.has(ref.name) && !local.has(ref.name))
    .map((ref) => ({ where: `${label}:${ref.line}`, name: ref.name }))
}

/* ── Load ──────────────────────────────────────────────────────────────────── */

const EXTENSIONS = new Set(['.astro', '.css', '.mdx'])

function styledFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true })
    .flatMap((entry) => {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) return styledFiles(full)
      return EXTENSIONS.has(extname(entry.name)) ? [full] : []
    })
    .sort()
}

const read = (file: string) => readFileSync(file, 'utf8')
/** `src/…` so a failure line pastes straight into an editor or a grep. */
const label = (file: string) => `src/${file.slice(SRC.length)}`

const files = styledFiles(SRC)

const globalProperties = new Set<string>()
for (const name of GLOBAL_STYLESHEETS) {
  const path = join(SRC, 'styles', name)
  for (const property of declaredProperties(cssRegions(read(path), '.css'))) {
    globalProperties.add(property)
  }
}

/* ── Tests ─────────────────────────────────────────────────────────────────── */

describe('the walk found the site', () => {
  // Everything below is a filter over these two collections. If the root moved, the
  // extension list went stale, or the declaration regex stopped matching, the gate
  // would exit 0 having checked nothing — which is indistinguishable from passing
  // until the day someone relies on it.
  it('collected the stylesheets, components and pages', () => {
    const labels = files.map(label)
    expect(labels.length).toBeGreaterThan(15)
    expect(labels).toContain('src/styles/tokens.css')
    expect(labels).toContain('src/components/Callout.astro')
  })

  it('collected the declared tokens', () => {
    expect(globalProperties.size).toBeGreaterThan(50)
    // A named member, so "the regex matched 112 somethings" is not the whole proof.
    expect([...globalProperties]).toContain('--space-s')
  })

  it('collected the references', () => {
    const total = files.reduce(
      (n, file) => n + varReferences(cssRegions(read(file), extname(file))).length,
      0,
    )
    expect(total).toBeGreaterThan(100)
  })

  it('has no tokens hiding in fonts.css', () => {
    // base.css `@import`s fonts.css too, so a custom property declared there would be
    // globally in scope at runtime but absent from GLOBAL_STYLESHEETS above — and
    // every legitimate use of it would be reported here as an offender. It declares
    // none today. If this ever fails, add fonts.css to GLOBAL_STYLESHEETS; do not
    // delete the test.
    const inFonts = declaredProperties(cssRegions(read(join(SRC, 'styles', 'fonts.css')), '.css'))
    expect([...inFonts]).toEqual([])
  })
})

describe('every var() resolves', () => {
  it('names no custom property that is declared nowhere', () => {
    const offenders = files.flatMap((file) =>
      offendersIn(label(file), read(file), extname(file), globalProperties),
    )

    const report = [
      `${offenders.length} var() reference(s) name a custom property that nothing declares.`,
      'Each one makes its whole declaration invalid at computed-value time and is',
      'dropped silently: padding -> 0, border-radius -> 0, font-size -> inherited.',
      'Fix by using an existing token from src/styles/tokens.css, declaring the token',
      'there, or — only where the property is genuinely optional — supplying a',
      'fallback: var(--x, 1rem).',
      '',
      ...offenders.map((o) => `  ${o.where}  ${o.name}`),
      '',
    ].join('\n')

    expect(offenders, report).toHaveLength(0)
  })
})

// Must-fail fixture. Without it, a change that broke `cssRegions` — a masking bug
// that blanked every `<style>` block, an extension list that stopped matching —
// would turn the assertion above green while checking nothing. It runs the exported
// helpers, not a copy of them, and against the site's real token set, so it also
// pins the three things that must NOT be reported.
describe('the extractor detects the failure it exists to catch', () => {
  const FIXTURE = [
    '---',
    '// Frontmatter is TypeScript, not CSS: var(--frontmatter-only) is prose here.',
    "const label = 'x'",
    '---',
    '<p class="f" style="gap: var(--space-s)">{label}</p>',
    '<style>',
    '  /* A commented-out var(--commented-out) is not CSS either. */',
    '  .f {',
    '    --local-thing: 4px;',
    '    padding: var(--local-thing);',
    '    margin: var(--definitely-not-a-token);',
    '    gap: var(--not-a-token-but-guarded, 1rem);',
    '  }',
    '</style>',
    '',
  ].join('\n')

  const offenders = offendersIn('fixture.astro', FIXTURE, '.astro', globalProperties)

  it('reports the undeclared reference, at its line', () => {
    expect(offenders).toEqual([{ where: 'fixture.astro:11', name: '--definitely-not-a-token' }])
  })

  it('reads inline style attributes, not just <style> blocks', () => {
    // `--space-s` only appears in the `style="…"` attribute. If masking dropped
    // attributes this would vanish, and so would the only thing checking them.
    const refs = varReferences(cssRegions(FIXTURE, '.astro')).map((r) => r.name)
    expect(refs).toContain('--space-s')
    expect(refs).not.toContain('--frontmatter-only')
    expect(refs).not.toContain('--commented-out')
  })

  it('accepts a property the file declares itself', () => {
    expect(declaredProperties(cssRegions(FIXTURE, '.astro'))).toEqual(new Set(['--local-thing']))
  })
})

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { findRepoRoot } from '../repo-facts/read.ts'

/**
 * Arithmetic over the palette in `src/styles/tokens.css`.
 *
 * Everything here reads the tokens out of the stylesheet rather than keeping its own
 * copy of the hexes, so editing a token moves the test. A second copy of the palette
 * in a test file is a palette that drifts.
 *
 * The WCAG 2.1 relative-luminance formula is implemented below rather than pulled
 * from a package: it is nine lines, it is frozen (WCAG 2.x will not change it), and
 * the alternative is a dependency on the deploy path of a site whose only npm surface
 * is Astro.
 *
 * Where a number is pinned with `toBeCloseTo`, it was independently recomputed and
 * the comment says what it means. Those pins exist so nobody has to rediscover them
 * — several are *constraints fixed by the design spec*, not defects to go and fix.
 */

/* ── WCAG 2.1 §1.4.3 relative luminance and contrast ratio ─────────────────── */

function relativeLuminance(hex: string): number {
  const body = hex.replace('#', '')
  const channels = [0, 2, 4]
    .map((i) => Number.parseInt(body.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}

function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const [lighter, darker] = la > lb ? [la, lb] : [lb, la]
  return (lighter + 0.05) / (darker + 0.05)
}

/* ── A very small CSS reader ───────────────────────────────────────────────── */

interface CssRule {
  /** The at-rule this sits inside, or null at the top level. */
  readonly atRule: string | null
  readonly selector: string
  readonly declarations: Readonly<Record<string, string>>
}

function parseDeclarations(body: string): Record<string, string> {
  const out: Record<string, string> = {}
  // No value in tokens.css contains a semicolon, so a naive split is exact here.
  for (const part of body.split(';')) {
    const colon = part.indexOf(':')
    if (colon === -1) continue
    const name = part.slice(0, colon).trim()
    if (!name.startsWith('--') && name !== 'color-scheme') continue
    out[name] = part
      .slice(colon + 1)
      .trim()
      .replace(/\s+/g, ' ')
  }
  return out
}

function parseRules(css: string, atRule: string | null = null, out: CssRule[] = []): CssRule[] {
  let i = 0
  let prelude = ''
  while (i < css.length) {
    const ch = css[i]
    if (ch === '{') {
      let depth = 1
      let j = i + 1
      while (j < css.length && depth > 0) {
        if (css[j] === '{') depth++
        else if (css[j] === '}') depth--
        j++
      }
      const body = css.slice(i + 1, j - 1)
      const head = prelude.trim()
      if (head.startsWith('@')) parseRules(body, head, out)
      else out.push({ atRule, selector: head, declarations: parseDeclarations(body) })
      prelude = ''
      i = j
      continue
    }
    if (ch === '}') {
      prelude = ''
      i++
      continue
    }
    prelude += ch
    i++
  }
  return out
}

/* ── Load ──────────────────────────────────────────────────────────────────── */

const repoRoot = findRepoRoot()
const tokensPath = join(repoRoot, 'web', 'src', 'styles', 'tokens.css')
const colorKtPath = join(
  repoRoot,
  'app/src/main/java/com/valhalla/thor/presentation/theme/Color.kt',
)

const rules = parseRules(readFileSync(tokensPath, 'utf8').replace(/\/\*[\s\S]*?\*\//g, ''))

const select = (selector: string, inMedia = false) =>
  rules.filter((r) =>
    inMedia
      ? r.atRule !== null &&
        /prefers-color-scheme:\s*light/.test(r.atRule) &&
        r.selector === selector
      : r.atRule === null && r.selector === selector,
  )

const rootRules = select(':root')
const mediaLightRule = select(':root', true)
const lightAttrRule = select("[data-theme='light']")
const darkAttrRule = select("[data-theme='dark']")
const amoledRule = select("[data-theme='dark'][data-amoled='true']")

/** The first `:root` block is the dark palette; the later ones are the site-only scales. */
const dark = rootRules[0].declarations
const light = lightAttrRule[0].declarations
/** AMOLED is dark with the three overrides applied, exactly as the cascade would. */
const amoled: Record<string, string> = { ...dark, ...amoledRule[0].declarations }

const PALETTE_TOKENS = Object.keys(dark).filter((k) => k.startsWith('--'))
const isHex = (value: string) => /^#[0-9a-f]{6}$/.test(value)

/** Every surface a page or a card can be painted with, darkest role name first. */
const SURFACES = [
  '--background',
  '--surface-container-lowest',
  '--surface-container-low',
  '--surface-container',
  '--surface-container-high',
  '--surface-container-highest',
] as const

/**
 * Foreground/background pairings the site uses for **normal-size** copy.
 *
 * `--error` on `--error-container` is in this list, and in the dark scheme it is the
 * one pair that fails — see the DANGER block below, which owns it. Everything else
 * must clear AA.
 */
function normalTextPairs(scheme: Record<string, string>): Array<[string, string, string]> {
  const pairs: Array<[string, string, string]> = []
  for (const surface of SURFACES) {
    for (const fg of ['--on-surface', '--on-surface-variant', '--primary'] as const) {
      pairs.push([`${fg} on ${surface}`, scheme[fg], scheme[surface]])
    }
  }
  for (const [fg, bg] of [
    ['--on-primary', '--primary'],
    ['--on-primary-container', '--primary-container'],
    ['--on-secondary', '--secondary'],
    ['--on-secondary-container', '--secondary-container'],
    ['--on-tertiary', '--tertiary'],
    ['--on-tertiary-container', '--tertiary-container'],
    ['--on-error', '--error'],
    // Body copy inside a danger callout. There is no `--on-error-container` to use.
    ['--on-surface', '--error-container'],
    ['--error', '--error-container'],
    ['--error', '--background'],
  ] as const) {
    pairs.push([`${fg} on ${bg}`, scheme[fg], scheme[bg]])
  }
  return pairs
}

/* ── Tests ─────────────────────────────────────────────────────────────────── */

describe('the contrast maths itself', () => {
  // A broken formula would make every assertion below pass vacuously. These three
  // are the tripwire.
  it('reproduces the two fixed points of the WCAG formula', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 6)
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 6)
    expect(contrastRatio('#4c662b', '#4c662b')).toBeCloseTo(1, 6)
  })

  it('reproduces a published reference value', () => {
    // #767676 on #ffffff is the canonical "exactly AA" grey, 4.54:1.
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 2)
  })
})

describe('syntax highlighting stays readable', () => {
  /**
   * Shiki's `css-variables` theme emits `var(--astro-code-*)` inline on every
   * highlighted span, with no fallback. Astro declares none of them in a
   * production build, so before tokens.css did, all 121 references in `dist`
   * were invalid at computed-value time and code samples rendered in one flat
   * inherited colour. Nothing could see it: the `var()`s are generated at build
   * time, and `token-usage.test.ts` walks `src/`.
   */
  const syntaxRule = rootRules.find((r) => '--astro-code-background' in r.declarations)

  it('declares every token Shiki actually emits', () => {
    expect(syntaxRule, 'tokens.css declares no --astro-code-* block').toBeDefined()
    // Pinned to what Shiki emits for the languages used on this site. A new
    // language introducing a seventh token would go undeclared and monochrome, so
    // this list is checked against `dist` by scripts/check-markup.mjs.
    expect(Object.keys(syntaxRule!.declarations).sort()).toEqual([
      '--astro-code-background',
      '--astro-code-foreground',
      '--astro-code-token-function',
      '--astro-code-token-punctuation',
      '--astro-code-token-string',
      '--astro-code-token-string-expression',
    ])
  })

  it('maps each one onto a palette token rather than a new hex', () => {
    // A literal here would be a site-only colour posing as the app's. It would
    // also escape every assertion in this file, since they all read the palette.
    for (const [token, value] of Object.entries(syntaxRule!.declarations)) {
      const referenced = /^var\((--[a-z-]+)\)$/.exec(value.trim())
      expect(referenced, `${token} is not a plain var() reference: ${value}`).not.toBeNull()
      expect(PALETTE_TOKENS, `${token} points at a token no palette declares`).toContain(
        referenced![1],
      )
    }
  })

  it('clears AA against the code-block background in all three themes', () => {
    // base.css paints `pre` with --surface-container, and the inline
    // --astro-code-background resolves to the same token, so that is the
    // background every one of these is read against. Code is normal-size copy:
    // the bar is 4.5:1, not 3:1.
    const resolve = (scheme: Record<string, string>, token: string) =>
      scheme[/^var\((--[a-z-]+)\)$/.exec(syntaxRule!.declarations[token]!.trim())![1]]!

    const foregrounds = Object.keys(syntaxRule!.declarations).filter(
      (t) => t !== '--astro-code-background',
    )

    for (const [name, scheme] of [
      ['dark', dark],
      ['light', light],
      ['amoled', amoled],
    ] as const) {
      const background = resolve(scheme, '--astro-code-background')
      for (const token of foregrounds) {
        expect(
          contrastRatio(resolve(scheme, token), background),
          `${token} on the code background in ${name}`,
        ).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('keeps the roles visually distinct from one another', () => {
    // Six tokens all resolving to --on-surface would satisfy every assertion
    // above and still be exactly the monochrome bug this block was added to fix.
    const distinct = new Set(Object.values(syntaxRule!.declarations).map((v) => v.trim()))
    expect(distinct.size).toBe(Object.keys(syntaxRule!.declarations).length)
  })
})

describe('tokens.css structure', () => {
  it('has exactly the five palette scopes, in the order the cascade needs', () => {
    // `:root`, `[data-theme='light']` and `[data-theme='dark']` all have specificity
    // (0,1,0), so source order is the only thing that resolves them. If the dark
    // override moved above the media query, a visitor on a light OS who chose dark
    // would silently get light.
    expect(mediaLightRule).toHaveLength(1)
    expect(lightAttrRule).toHaveLength(1)
    expect(darkAttrRule).toHaveLength(1)
    expect(amoledRule).toHaveLength(1)

    // Bare `:root` is dropped here because the site-only scales at the bottom of the
    // file reuse it; what matters is the order of the four overriding scopes.
    const overrideOrder = rules
      .map((r) => (r.atRule ? `@media ${r.selector}` : r.selector))
      .filter((key) => key !== ':root')
    expect(overrideOrder).toEqual([
      '@media :root',
      "[data-theme='light']",
      "[data-theme='dark']",
      "[data-theme='dark'][data-amoled='true']",
    ])
  })

  it('declares the same 32 palette tokens in both schemes', () => {
    expect(PALETTE_TOKENS).toHaveLength(32)
    expect(Object.keys(light).filter((k) => k.startsWith('--')).sort()).toEqual(
      [...PALETTE_TOKENS].sort(),
    )
  })

  it('never aliases one palette token to another', () => {
    // The trap: `--surface-variant` and `--surface-container-highest` are both
    // #262626 in dark, so `--surface-variant: var(--surface-container-highest)` looks
    // right in light, in dark, and in every review. AMOLED splits them and the bug
    // ships. Same shape in light, where `--surface-variant` equals
    // `--surface-container`. Every palette value must be a literal.
    for (const token of PALETTE_TOKENS) {
      expect(dark[token], `dark ${token}`).not.toContain('var(')
      expect(light[token], `light ${token}`).not.toContain('var(')
    }
  })

  it('keeps the two copies of the light palette identical', () => {
    // A media condition cannot be folded into a selector, so "the OS says light" and
    // "the visitor chose light" have to be two rules. This is what stops them drifting.
    expect(mediaLightRule[0].declarations).toEqual(lightAttrRule[0].declarations)
  })

  it('keeps the two copies of the dark palette identical', () => {
    expect(rootRules[0].declarations).toEqual(darkAttrRule[0].declarations)
  })

  it('declares no onErrorContainer, because the app has none', () => {
    // Color.kt declares OnErrorContainer and Theme.kt passes it to neither scheme, so
    // at runtime it falls through to a Material 3 baseline. Importing that baseline
    // would put Material You colour into a palette whose point is not being Material
    // You. Danger callouts use `--on-surface` on `--error-container` instead.
    expect(PALETTE_TOKENS).not.toContain('--on-error-container')
    expect(Object.keys(light)).not.toContain('--on-error-container')
  })

  it('leaves the four dark-only tokens with no light value', () => {
    // The light scheme omits inversePrimary, inverseSurface, inverseOnSurface and
    // surfaceTint entirely. `initial` makes the custom property guaranteed-invalid,
    // which blocks the dark value from inheriting into a light page. Do not invent
    // light values for these; do not use them where a light page can reach.
    for (const token of [
      '--inverse-surface',
      '--inverse-on-surface',
      '--inverse-primary',
      '--surface-tint',
    ]) {
      expect(isHex(dark[token]), `dark ${token} should be a colour`).toBe(true)
      expect(light[token], `light ${token}`).toBe('initial')
    }
  })
})

describe('AMOLED', () => {
  it('overrides exactly three tokens, all to black', () => {
    // Theme.kt: AsgardianDarkColorScheme.copy(background, surface, surfaceVariant =
    // Color.Black). Three. If this test starts failing because a fourth was added,
    // the app changed — go and check Theme.kt before changing the expectation.
    expect(amoledRule[0].declarations).toEqual({
      '--background': '#000000',
      '--surface': '#000000',
      '--surface-variant': '#000000',
    })
  })

  it('leaves the whole surface-container ramp alone', () => {
    // A #191919 card on a #000000 page is the intended look. Flattening the
    // containers to black too is wrong, and it is the obvious "fix" to reach for.
    for (const surface of SURFACES) {
      if (surface === '--background') continue
      expect(amoled[surface], surface).toBe(dark[surface])
    }
    expect(contrastRatio(amoled['--surface-container'], amoled['--background'])).toBeCloseTo(
      1.19,
      2,
    )
  })

  it('splits surface-variant from surface-container-highest', () => {
    // Both are #262626 in dark. This is the assertion the aliasing trap would break.
    expect(dark['--surface-variant']).toBe(dark['--surface-container-highest'])
    expect(amoled['--surface-variant']).not.toBe(amoled['--surface-container-highest'])
  })
})

describe('hairlines are decorative, never a control boundary', () => {
  // `--outline-variant` is the app's hairline. Both values sit far below the 3:1 that
  // WCAG 1.4.11 requires of the visible boundary of a user-interface component, and
  // that is fine — a rule between two paragraphs is not a control. What must never
  // happen is a button, input, chip, focus target or data-table rule whose only
  // boundary is this colour. Those use `--outline`.
  it('light hairline #c3c8bc on #f8faf3 is 1.62:1', () => {
    expect(contrastRatio(light['--outline-variant'], light['--background'])).toBeCloseTo(1.62, 2)
  })

  it('dark hairline #484848 on #0e0e0e is 2.11:1', () => {
    expect(contrastRatio(dark['--outline-variant'], dark['--background'])).toBeCloseTo(2.11, 2)
  })
})

describe('strong borders clear the non-text threshold', () => {
  it('--outline is 4.25:1 in light and 4.19:1 in dark against the page', () => {
    expect(contrastRatio(light['--outline'], light['--background'])).toBeCloseTo(4.25, 2)
    expect(contrastRatio(dark['--outline'], dark['--background'])).toBeCloseTo(4.19, 2)
  })

  it('--outline stays above 3:1 on every surface in the ramp, in all three modes', () => {
    // The floor is 3.28:1 — `--outline` on `--surface-container-highest` in dark.
    for (const [name, scheme] of [
      ['light', light],
      ['dark', dark],
      ['amoled', amoled],
    ] as const) {
      for (const surface of SURFACES) {
        const ratio = contrastRatio(scheme['--outline'], scheme[surface])
        expect(ratio, `${name}: --outline on ${surface} = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(3)
      }
    }
  })
})

describe('cards are a texture, not a boundary', () => {
  it('dark card #191919 on #0e0e0e is 1.10:1', () => {
    // Nowhere near a visible edge on its own. A card that has to read as a distinct
    // region needs a border in `--outline` or `--outline-variant`, not just the fill.
    expect(contrastRatio(dark['--surface-container'], dark['--background'])).toBeCloseTo(1.1, 2)
  })
})

describe('danger', () => {
  it('dark #fe7453 on #881f05 is 3.49:1 — large text and icons only', () => {
    // BELOW AA (4.5:1) for normal-size text, and both values are fixed by the design
    // spec, so this is a constraint rather than a defect. It is pinned at the
    // large-text / non-text threshold (3:1) so the pairing stays usable for a callout
    // heading (>=18.66px bold or >=24px) and for icons, and so nobody rediscovers the
    // number by shipping unreadable body copy. Danger body text uses `--on-surface`
    // on `--error-container`: 7.43:1 dark, 13.31:1 light.
    const ratio = contrastRatio(dark['--error'], dark['--error-container'])
    expect(ratio).toBeCloseTo(3.49, 2)
    expect(ratio).toBeGreaterThanOrEqual(3)
  })

  it('gives danger body copy a foreground that clears AA in both schemes', () => {
    expect(contrastRatio(dark['--on-surface'], dark['--error-container'])).toBeGreaterThanOrEqual(
      4.5,
    )
    expect(
      contrastRatio(light['--on-surface'], light['--error-container']),
    ).toBeGreaterThanOrEqual(4.5)
  })

  it('gives the danger callout LABEL the same foreground, and it clears AA too', () => {
    // Callout.astro reached for `var(--on-error-container)` on both the label and
    // the body. No such token exists — Theme.kt passes `onErrorContainer` to neither
    // scheme — so both sites resolved to the initial value and inherited whatever
    // was above them. `--on-surface` is the replacement at BOTH sites.
    //
    // The label is not exempt just because it is the biggest text in the box:
    // `--text-body-large` is 1.0625rem = 17px at the root size, and WCAG large text
    // starts at 18.66px bold / 24px regular. So the 3:1 large-text allowance that
    // lets `--error` sit on `--error-container` does not reach it, and 4.5:1 is the
    // bar the label has to clear as well.
    expect(contrastRatio(dark['--on-surface'], dark['--error-container'])).toBeCloseTo(7.43, 2)
    expect(contrastRatio(light['--on-surface'], light['--error-container'])).toBeCloseTo(13.31, 2)

    // And the alternative that looks right and is not: `--on-surface-variant` passes
    // in light (7.19:1) and fails in dark (4.07:1). Checking one scheme is how that
    // ships.
    expect(
      contrastRatio(dark['--on-surface-variant'], dark['--error-container']),
    ).toBeLessThan(4.5)
  })
})

describe('normal-size text clears WCAG AA', () => {
  for (const [name, scheme] of [
    ['light', light],
    ['dark', dark],
    ['amoled', amoled],
  ] as const) {
    it(`${name}: every pairing is at least 4.5:1`, () => {
      for (const [label, fg, bg] of normalTextPairs(scheme)) {
        // The one documented exception, owned by the `danger` block above.
        if (name !== 'light' && label === '--error on --error-container') continue
        const ratio = contrastRatio(fg, bg)
        expect(ratio, `${name}: ${label} = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5)
      }
    })
  }

  it('the lowest legitimate pairing is light --error on --error-container, 5.00:1', () => {
    // Pinned so a palette edit that quietly lowers the floor shows up as a diff on
    // this number rather than as an unreadable page.
    const ranked = (['light', 'dark', 'amoled'] as const)
      .flatMap((name) =>
        normalTextPairs(name === 'light' ? light : name === 'dark' ? dark : amoled)
          .filter(([label]) => name === 'light' || label !== '--error on --error-container')
          .map(([label, fg, bg]) => ({ key: `${name}: ${label}`, ratio: contrastRatio(fg, bg) })),
      )
      .sort((a, b) => a.ratio - b.ratio)

    expect(ranked[0].key).toBe('light: --error on --error-container')
    expect(ranked[0].ratio).toBeCloseTo(5.0, 2)
  })
})

describe('the focus ring is visible everywhere it can land', () => {
  it('--primary clears the 3:1 non-text threshold on every surface, in all three modes', () => {
    // `--focus-ring-color` is `var(--primary)`. The floor is 7.21:1 (light, on
    // --surface-container-highest), so there is a lot of headroom — but a palette
    // change to `--primary` is a change to the focus ring, and this is what says so.
    for (const [name, scheme] of [
      ['light', light],
      ['dark', dark],
      ['amoled', amoled],
    ] as const) {
      for (const surface of SURFACES) {
        const ratio = contrastRatio(scheme['--primary'], scheme[surface])
        expect(ratio, `${name}: --primary on ${surface} = ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(3)
      }
    }
  })
})

describe('every colour traces back to Color.kt', () => {
  // Color.kt writes its hexes lowercase behind an `0xff` prefix — `Color(0xff4c662b)`.
  // tokens.css writes them lowercase behind `#`. This comparison is therefore both
  // case-insensitive and 0xff-aware, and it is the mechanical form of the rule that
  // the site invents no colour: if a hex is not in Color.kt, it did not come from the
  // app.
  const colorKt = readFileSync(colorKtPath, 'utf8')
  const declared = new Set(
    [...colorKt.matchAll(/0x([0-9a-fA-F]{8})/g)].map((m) => m[1].toLowerCase().slice(2)),
  )

  it('found the Kotlin colours to compare against', () => {
    // Without this, a moved file or a changed literal syntax would empty the set and
    // the assertions below would still run — just against nothing useful.
    expect(declared.size).toBeGreaterThan(50)
    expect(declared.has('4c662b')).toBe(true)
  })

  for (const [name, scheme] of [
    ['light', light],
    ['dark', dark],
    ['amoled', amoled],
  ] as const) {
    it(`${name}: no invented hexes`, () => {
      for (const token of PALETTE_TOKENS) {
        const value = scheme[token]
        if (!isHex(value)) continue // the four dark-only tokens are `initial` in light
        expect(declared.has(value.slice(1)), `${name} ${token} = ${value}`).toBe(true)
      }
    })
  }
})

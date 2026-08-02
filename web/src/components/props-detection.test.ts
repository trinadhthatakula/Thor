/**
 * Asserts that every component declaring `interface Props` actually gets its
 * props type-checked at call sites.
 *
 * This exists because that guarantee can be lost silently. The Astro compiler
 * finds `Props` by scanning the frontmatter, and an **unbalanced `<` anywhere
 * in that frontmatter — including inside a comment** — makes the scanner treat
 * the rest of the file as an unterminated type argument list, give up, and emit
 *
 *     export default function X__AstroComponent_(_props: Record<string, any>)
 *
 * instead of `(_props: Props)`. Nothing errors. `astro check` reports zero
 * problems. Every call site then accepts anything — a misspelled prop, a number
 * where a string union belongs, even a missing required prop.
 *
 * `Fact.astro` shipped in exactly that state: its own header comment said "grep
 * for `<Fact`", the bare `<` disabled the check, and the component whose entire
 * purpose is "a typo is a build error rather than a blank span in production"
 * was the one component in the site not enforcing it.
 *
 * The check runs against the compiler's real output rather than a regex over
 * source, because the failure is in what the compiler *emits*, not in what the
 * source appears to say.
 */
import { convertToTSX } from '@astrojs/compiler'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const SRC = fileURLToPath(new URL('..', import.meta.url))

function astroFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) return astroFiles(full)
    return entry.name.endsWith('.astro') ? [full] : []
  })
}

/** The `_props: …` annotation the compiler emitted for a component's default export. */
async function emittedPropsType(source: string, filename: string): Promise<string | null> {
  const { code } = await convertToTSX(source, { filename })
  return /export default function \w+\((_props: [^)]*)\)/.exec(code)?.[1] ?? null
}

const DECLARES_PROPS = /^\s*(export\s+)?(interface|type)\s+Props\b/m

describe('Astro props detection', () => {
  const files = astroFiles(SRC).filter((f) => DECLARES_PROPS.test(readFileSync(f, 'utf8')))

  it('finds components to check', () => {
    // A wrong root would make every assertion below vacuously pass.
    expect(files.length).toBeGreaterThan(5)
  })

  it.each(files.map((f) => [f.slice(SRC.length), f]))(
    '%s declares Props, so the compiler must type its call sites',
    async (_label, file) => {
      const emitted = await emittedPropsType(readFileSync(file, 'utf8'), file)
      expect(emitted).toBe('_props: Props')
    },
  )

  // Must-fail fixture. Without this, a change that broke `emittedPropsType`
  // itself — a renamed export, a compiler output format change — would turn
  // every assertion above green for the wrong reason.
  it('detects the unbalanced-`<`-in-a-comment failure it exists to catch', async () => {
    const body = "\ninterface Props { name: 'a' | 'b' }\nconst { name } = Astro.props\n---\n<span>{name}</span>"
    const good = await emittedPropsType(`---\n/** grep for \`<Fact />\` */${body}`, 'good.astro')
    const bad = await emittedPropsType(`---\n/** grep for \`<Fact\` */${body}`, 'bad.astro')

    expect(good).toBe('_props: Props')
    expect(bad).toBe('_props: Record<string, any>')
  })
})

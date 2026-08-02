/**
 * Pure parsers for the two repo files the site derives facts from. No I/O lives
 * here — `read.ts` does the file access, so everything below is testable against
 * inline fixtures.
 *
 * This file is the fourth place in the repo that knows how a `versionCode`
 * becomes a `versionName`. The other three are `app/build.gradle.kts`,
 * `.github/scripts/sync-shizu-changelog.sh` and
 * `.github/scripts/check-shizu-manifest.sh`, and they carry mutual `LOCKSTEP:`
 * comments. `lockstep.test.ts` fails if the Gradle arithmetic drifts from ours.
 *
 * Two deliberate divergences from `app/build.gradle.kts:50-76`:
 *
 *   1. Gradle **throws** when `initialVersionCode` is absent and `versionCode`
 *      is too. We only require `versionCode`; `initialVersionCode` is a
 *      fallback, not a requirement, because the site has no use for the
 *      bootstrap path Gradle needs it for.
 *   2. We do not emulate `-PversionName=…` command-line overrides or
 *      `~/.gradle/gradle.properties`. The site renders what is committed, which
 *      is the only thing a reader can check for themselves.
 */

/** Thrown by every validation failure here, so callers can tell a parse problem from an I/O one. */
export class RepoFactsError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'RepoFactsError'
  }
}

/**
 * Parse a `.properties` file the way `java.util.Properties` does, for the subset
 * `gradle.properties` actually uses.
 *
 * Supported: `#` and `!` comment lines, `=` / `:` / bare-whitespace separators,
 * CRLF, backslash line continuations, and leading whitespace.
 *
 * **Last duplicate key wins**, which is what Java does. The repo is currently
 * inconsistent about this — `production-deploy.yml:65` uses `tail -n 1` and
 * `dev-check.yml:104` uses `head -n1` — so it is worth stating rather than
 * inheriting by accident.
 */
export function parseJavaProperties(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  const physical = text.split(/\r\n|\n|\r/)

  const logical: string[] = []
  let pending: string | null = null
  for (const raw of physical) {
    // Leading whitespace is stripped from *every* physical line, continuation
    // lines included — `Properties.LineReader` sets `skipWhiteSpace = true`
    // again after each backslash. So there is deliberately no branch on
    // `pending` here; one would also make `line` and `pending` mutually
    // dependent and leave `line` inferred as `any`.
    const line = raw.replace(/^[ \t\f]+/, '')
    if (pending === null && (line === '' || line.startsWith('#') || line.startsWith('!'))) continue

    // An odd number of trailing backslashes continues onto the next line.
    const trailing = /(\\*)$/.exec(line)?.[1].length ?? 0
    if (trailing % 2 === 1) {
      pending = (pending ?? '') + line.slice(0, -1)
      continue
    }
    logical.push((pending ?? '') + line)
    pending = null
  }
  if (pending !== null) logical.push(pending)

  for (const line of logical) {
    // Find the first unescaped separator.
    let sep = -1
    let sepLen = 1
    for (let i = 0; i < line.length; i++) {
      const c = line[i]
      if (c === '\\') {
        i++
        continue
      }
      if (c === '=' || c === ':') {
        sep = i
        break
      }
      if (c === ' ' || c === '\t' || c === '\f') {
        // Bare whitespace separates only if no `=`/`:` follows before the value.
        const rest = line.slice(i).replace(/^[ \t\f]+/, '')
        if (rest.startsWith('=') || rest.startsWith(':')) {
          sep = line.length - rest.length
          break
        }
        sep = i
        sepLen = 0
        break
      }
    }
    if (sep === -1) {
      const key = unescapeProperty(line).trim()
      if (key !== '') out[key] = ''
      continue
    }
    const key = unescapeProperty(line.slice(0, sep)).trim()
    const value = unescapeProperty(line.slice(sep + sepLen).replace(/^[ \t\f]*[=:]?[ \t\f]*/, ''))
    if (key !== '') out[key] = value
  }
  return out
}

function unescapeProperty(s: string): string {
  return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, esc: string) => {
    if (esc[0] === 'u') return String.fromCharCode(parseInt(esc.slice(1), 16))
    switch (esc) {
      case 'n':
        return '\n'
      case 't':
        return '\t'
      case 'r':
        return '\r'
      case 'f':
        return '\f'
      default:
        return esc
    }
  })
}

/**
 * Mirror of `resolveVersionCode()` in `app/build.gradle.kts`: prefer `versionCode`,
 * fall back to `initialVersionCode`.
 *
 * The lookup is an **exact key match after trimming** — never a substring, never
 * `endsWith`, never case-insensitive. `initialVersionCode=1921` sits four lines
 * above `versionCode=1931` in `gradle.properties`, so a loose match silently
 * yields 1921 and the site prints "1.92.1" for a 1.93.1 build. That is the exact
 * class of confidently-wrong fact this module exists to prevent, and
 * `parse.test.ts` pins it.
 */
export function resolveVersionCode(props: Record<string, string>): number {
  const raw = props['versionCode'] ?? props['initialVersionCode']
  if (raw === undefined || raw.trim() === '') {
    throw new RepoFactsError(
      'gradle.properties: neither `versionCode` nor `initialVersionCode` is set. ' +
        'The site derives every version it prints from `versionCode`; add it there.',
    )
  }
  const trimmed = raw.trim()
  if (!/^\d+$/.test(trimmed)) {
    throw new RepoFactsError(
      `gradle.properties: \`versionCode\` is "${trimmed}", which is not a positive integer. ` +
        'Expected a bare number such as 1931.',
    )
  }
  const code = Number(trimmed)
  if (code < 1000) {
    throw new RepoFactsError(
      `gradle.properties: \`versionCode\` is ${code}, below 1000. ` +
        'The version-name arithmetic assumes four digits — 999 would derive "0.99.9". ' +
        'If the scheme really changed, update web/src/lib/repo-facts/parse.ts too.',
    )
  }
  return code
}

/**
 * `1931 -> "1.93.1"`.
 *
 * LOCKSTEP: `app/build.gradle.kts` `calculateVersionName`,
 * `.github/scripts/sync-shizu-changelog.sh`, `.github/scripts/check-shizu-manifest.sh`.
 * `lockstep.test.ts` reads the Gradle file and fails if the three expressions below
 * are no longer present there.
 */
export function deriveVersionName(code: number): string {
  if (!Number.isInteger(code) || code < 1000) {
    throw new RepoFactsError(
      `deriveVersionName expected an integer >= 1000, got ${String(code)}.`,
    )
  }
  const major = Math.floor(code / 1000)
  const minor = Math.floor((code % 1000) / 10)
  const patch = code % 10
  return `${major}.${minor}.${patch}`
}

/**
 * Read the `[versions]` table out of a Gradle version catalog.
 *
 * Parsed with a real TOML parser and scoped to `[versions]` on purpose: eight bare
 * keys — `androidx-adaptive`, `junit`, `koin`, `ksp`, `odin`, `play-billing`,
 * `room`, `turbine` — appear in both `[versions]` and another table, so a
 * whole-file regex returns whichever one it happens to hit first.
 */
export function parseCatalogVersions(
  text: string,
  parseToml: (t: string) => unknown,
): Record<string, string> {
  let doc: unknown
  try {
    doc = parseToml(text)
  } catch (cause) {
    throw new RepoFactsError(
      `gradle/libs.versions.toml is not valid TOML: ${(cause as Error).message}`,
    )
  }
  const versions = (doc as Record<string, unknown> | null)?.['versions']
  if (versions === undefined || versions === null || typeof versions !== 'object') {
    throw new RepoFactsError(
      'gradle/libs.versions.toml has no `[versions]` table. ' +
        'The site reads compileSdk, targetSdk, minSdk and thorExtensionApi from it.',
    )
  }
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(versions as Record<string, unknown>)) {
    if (typeof v === 'string') out[k] = v
  }
  return out
}

/** An SDK level as a number. Range-checked so a typo cannot render as a plausible-looking level. */
export function readSdkInt(versions: Record<string, string>, key: string): number {
  const raw = versions[key]
  if (raw === undefined) {
    throw new RepoFactsError(
      `gradle/libs.versions.toml: \`[versions] ${key}\` is missing. The site prints it verbatim.`,
    )
  }
  if (!/^\d+$/.test(raw.trim())) {
    throw new RepoFactsError(
      `gradle/libs.versions.toml: \`[versions] ${key} = "${raw}"\` is not a bare integer.`,
    )
  }
  const n = Number(raw.trim())
  if (n < 1 || n > 99) {
    throw new RepoFactsError(
      `gradle/libs.versions.toml: \`[versions] ${key}\` is ${n}, outside the plausible 1..99 range for an Android API level.`,
    )
  }
  return n
}

/**
 * A version string, returned **as a string**.
 *
 * Never strip non-digits. The repo's `tr -dc '0-9'` idiom turns `"3.0.0"` into
 * `300`, and `300` printed as an extension API version on `/build-an-extension`
 * is a dependency coordinate that does not resolve.
 */
export function readSemver(versions: Record<string, string>, key: string): string {
  const raw = versions[key]
  if (raw === undefined) {
    throw new RepoFactsError(
      `gradle/libs.versions.toml: \`[versions] ${key}\` is missing.`,
    )
  }
  const value = raw.trim()
  if (!/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(value)) {
    throw new RepoFactsError(
      `gradle/libs.versions.toml: \`[versions] ${key} = "${raw}"\` is not a semantic version. ` +
        'The site prints it inside a dependency coordinate, so it has to be exact.',
    )
  }
  return value
}

/**
 * API level to the marketing name a reader recognises.
 *
 * Hand-maintained and **throws** on an unknown level, on purpose. Only `minSdk` is
 * mapped: API 37 has no settled public name, and inventing one is precisely the
 * confidently-wrong prose this module exists to prevent.
 */
const ANDROID_NAMES: Readonly<Record<number, string>> = Object.freeze({
  26: 'Android 8.0',
  27: 'Android 8.1',
  28: 'Android 9',
  29: 'Android 10',
  30: 'Android 11',
  31: 'Android 12',
  32: 'Android 12L',
  33: 'Android 13',
  34: 'Android 14',
  35: 'Android 15',
  36: 'Android 16',
})

export function androidNameForApi(level: number): string {
  const name = ANDROID_NAMES[level]
  if (name === undefined) {
    throw new RepoFactsError(
      `No marketing name is recorded for API ${level}. Add it to ANDROID_NAMES in ` +
        'web/src/lib/repo-facts/parse.ts — do not guess one into the page.',
    )
  }
  return name
}

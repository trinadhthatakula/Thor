/**
 * File access for the repo facts. Everything here fails loudly.
 *
 * The spec's rule is that a failed build is better than a wrong fact, and a failed
 * build only makes the site stale — Vercel keeps serving the last good deployment.
 * So there is no `?? 'unknown'`, no empty-string default and no swallowing
 * try/catch anywhere below.
 */
import { readFileSync } from 'node:fs'
import { dirname, join, parse as parsePath, resolve } from 'node:path'
import { parse as parseToml } from 'smol-toml'
import {
  RepoFactsError,
  parseCatalogVersions,
  parseJavaProperties,
} from './parse.ts'

/** The only two files outside `web/` that the build reads. */
export const GRADLE_PROPERTIES = 'gradle.properties'
export const VERSION_CATALOG = join('gradle', 'libs.versions.toml')

/**
 * Walk up from `startDir` until a directory contains `settings.gradle.kts`.
 *
 * Deliberately **not** resolved from `import.meta.url`: Vite bundles `src/lib/**`
 * into the SSR output, so that URL points at an emitted chunk. It resolves fine
 * under Vitest and breaks under `astro build` — the worst possible shape for a
 * bug. Walking up from the working directory also means the build is correct
 * whether it runs from `web/` or from the repo root.
 */
export function findRepoRoot(startDir: string = process.cwd()): string {
  let dir = resolve(startDir)
  const { root } = parsePath(dir)
  for (;;) {
    try {
      readFileSync(join(dir, 'settings.gradle.kts'), 'utf8')
      return dir
    } catch {
      // keep walking
    }
    if (dir === root) break
    dir = dirname(dir)
  }
  throw new RepoFactsError(
    `Could not find the Thor repository root above ${resolve(startDir)} — no ancestor ` +
      'directory contains settings.gradle.kts.\n\n' +
      'On Vercel this almost always means "Include files outside of the Root Directory in ' +
      'the Build Step" is OFF. The site derives its version and SDK numbers from ' +
      'gradle.properties and gradle/libs.versions.toml, which live above web/, so that ' +
      'setting has to stay ON.',
  )
}

function read(root: string, relative: string): string {
  const path = join(root, relative)
  try {
    return readFileSync(path, 'utf8')
  } catch (cause) {
    throw new RepoFactsError(
      `Could not read ${relative} at ${path}: ${(cause as Error).message}\n\n` +
        'On Vercel this almost always means "Include files outside of the Root Directory in ' +
        'the Build Step" is OFF.',
    )
  }
}

export interface RawRepoFiles {
  readonly root: string
  readonly properties: Record<string, string>
  readonly catalogVersions: Record<string, string>
}

/** Read and parse both files. Throws with an actionable message on any failure. */
export function readRepoFiles(root: string = findRepoRoot()): RawRepoFiles {
  return {
    root,
    properties: parseJavaProperties(read(root, GRADLE_PROPERTIES)),
    catalogVersions: parseCatalogVersions(read(root, VERSION_CATALOG), parseToml),
  }
}

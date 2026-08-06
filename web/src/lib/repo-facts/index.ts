/**
 * The single source of every number the site states about the app.
 *
 * Computed once at module scope and frozen. If either file is unreadable or
 * either value is implausible, this throws and the build fails — which is the
 * intended outcome. A failed build leaves the previous deployment serving; a
 * swallowed error ships a wrong version number to every reader.
 *
 * Nothing outside `src/components/Fact.astro` should import `repoFacts` directly.
 * That component is the one grep target for "where does the site print a number".
 */
import {
  androidNameForApi,
  deriveVersionName,
  readSdkInt,
  readSemver,
  resolveVersionCode,
} from './parse.ts'
import { readRepoFiles } from './read.ts'
import type { RepoFacts } from './types.ts'

export { RepoFactsError } from './parse.ts'
export type { RepoFacts } from './types.ts'

export function computeRepoFacts(files = readRepoFiles()): RepoFacts {
  const versionCode = resolveVersionCode(files.properties)
  const versionName = deriveVersionName(versionCode)
  const minSdk = readSdkInt(files.catalogVersions, 'minSdk')
  const targetSdk = readSdkInt(files.catalogVersions, 'targetSdk')
  const compileSdk = readSdkInt(files.catalogVersions, 'compileSdk')

  if (!(minSdk <= targetSdk && targetSdk <= compileSdk)) {
    throw new Error(
      `SDK levels are out of order: minSdk=${minSdk}, targetSdk=${targetSdk}, compileSdk=${compileSdk}. ` +
        'Expected minSdk <= targetSdk <= compileSdk.',
    )
  }

  return Object.freeze({
    versionCode,
    versionName,
    minSdk,
    targetSdk,
    compileSdk,
    minSdkAndroidName: androidNameForApi(minSdk),
    extensionApiVersion: readSemver(files.catalogVersions, 'thorExtensionApi'),
  })
}

export const repoFacts: RepoFacts = computeRepoFacts()

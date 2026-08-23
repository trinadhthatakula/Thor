/**
 * Submits the site's canonical URLs to IndexNow (Bing, Copilot, Yandex, Seznam, Naver).
 *
 * ## IndexNow Protocol
 *
 * IndexNow allows website owners to instantly notify participating search engines
 * whenever content is published, updated, or deleted.
 *
 * Key components:
 * 1. Verification key hosted at `<origin>/<key>.txt`
 * 2. POST payload to https://api.indexnow.org/indexnow
 */
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { INDEXNOW, ROUTES, SITE, canonical } from '../src/lib/site.ts'
import { dirArg, isMain } from './lib/report.mjs'

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))
const WEB_DIR = join(SCRIPT_DIR, '..')

/**
 * Builds the payload for IndexNow protocol.
 */
export function buildIndexNowPayload(urls, options = {}) {
  const host = options.host ?? new URL(SITE.origin).host
  const key = options.key ?? INDEXNOW.key
  const keyLocation = options.keyLocation ?? INDEXNOW.keyLocation

  return {
    host,
    key,
    keyLocation,
    urlList: urls,
  }
}

/**
 * Collects canonical URLs to submit from site routes.
 */
export function collectSiteUrls() {
  return ROUTES.map((route) => canonical(route.href))
}

/**
 * Verifies that the IndexNow key file exists in the public or dist directory.
 */
export function verifyKeyFile(dir = join(WEB_DIR, 'public'), key = INDEXNOW.key) {
  const keyPath = join(dir, `${key}.txt`)
  if (!existsSync(keyPath)) {
    return { ok: false, error: `Key file not found at ${keyPath}` }
  }
  const content = readFileSync(keyPath, 'utf8').trim()
  if (content !== key) {
    return {
      ok: false,
      error: `Key file content mismatch: expected "${key}", got "${content}"`,
    }
  }
  return { ok: true, keyPath }
}

/**
 * Submits URL list to the IndexNow API.
 */
export async function submitIndexNow(options = {}) {
  const urls = options.urls ?? collectSiteUrls()
  const endpoint = options.endpoint ?? INDEXNOW.endpoint
  const dryRun = options.dryRun ?? false
  const fetchFn = options.fetchFn ?? globalThis.fetch
  const signal = options.signal ?? AbortSignal.timeout(10_000)

  const payload = buildIndexNowPayload(urls, options)

  if (dryRun) {
    return {
      ok: true,
      status: 200,
      dryRun: true,
      payload,
      urlsCount: urls.length,
    }
  }

  try {
    const response = await fetchFn(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'User-Agent': 'Thor-IndexNow-Client/1.0',
      },
      body: JSON.stringify(payload),
      signal,
    })

    // 200 (OK) or 202 (Accepted) indicates success per IndexNow spec
    const ok = response.status === 200 || response.status === 202
    return {
      ok,
      status: response.status,
      statusText: response.statusText,
      payload,
      urlsCount: urls.length,
    }
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error ? err.message : String(err),
      payload,
      urlsCount: urls.length,
    }
  }
}

/**
 * Entry point when executed via CLI.
 */
async function main() {
  const isDryRun = process.argv.includes('--dry-run')
  const targetDir = dirArg() || join(WEB_DIR, 'dist')

  // Verify key file in the target build directory
  const keyCheck = verifyKeyFile(targetDir)
  if (!keyCheck.ok) {
    console.error(`submit-indexnow: ERROR — ${keyCheck.error}`)
    process.exit(1)
  }

  const urls = collectSiteUrls()
  console.log(`submit-indexnow: Submitting ${urls.length} URLs to IndexNow (${isDryRun ? 'DRY RUN' : 'LIVE'})...`)

  const result = await submitIndexNow({ urls, dryRun: isDryRun })
  if (result.ok) {
    console.log(
      `submit-indexnow: OK — ${result.urlsCount} URLs submitted (HTTP ${result.status ?? 'DRY-RUN'}) to ${INDEXNOW.endpoint}`,
    )
    process.exit(0)
  } else {
    console.error(
      `submit-indexnow: FAILED — HTTP ${result.status}: ${result.statusText || result.error}`,
    )
    process.exit(1)
  }
}

if (isMain(import.meta.url)) {
  main()
}

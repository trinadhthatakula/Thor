import { describe, expect, it, vi } from 'vitest'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  buildIndexNowPayload,
  collectSiteUrls,
  submitIndexNow,
  verifyKeyFile,
} from './submit-indexnow.mjs'
import { INDEXNOW, SITE } from '../src/lib/site.ts'

const SCRIPT_DIR = fileURLToPath(new URL('.', import.meta.url))
const WEB_DIR = join(SCRIPT_DIR, '..')

describe('IndexNow protocol implementation', () => {
  it('has a valid 32-character hexadecimal key in public/', () => {
    expect(INDEXNOW.key).toMatch(/^[0-9a-f]{32}$/)
    const check = verifyKeyFile(join(WEB_DIR, 'public'), INDEXNOW.key)
    expect(check.ok).toBe(true)
  })

  it('collects all site canonical URLs', () => {
    const urls = collectSiteUrls()
    expect(urls.length).toBeGreaterThan(0)
    for (const u of urls) {
      expect(u.startsWith(SITE.origin)).toBe(true)
    }
  })

  it('builds a valid IndexNow JSON payload', () => {
    const urls = ['https://thor.trinadhthatakula.com/', 'https://thor.trinadhthatakula.com/features']
    const payload = buildIndexNowPayload(urls)

    expect(payload).toEqual({
      host: 'thor.trinadhthatakula.com',
      key: INDEXNOW.key,
      keyLocation: INDEXNOW.keyLocation,
      urlList: urls,
    })
  })

  it('performs dry-run submission without network calls', async () => {
    const urls = ['https://thor.trinadhthatakula.com/']
    const result = await submitIndexNow({ urls, dryRun: true })

    expect(result.ok).toBe(true)
    expect(result.dryRun).toBe(true)
    expect(result.status).toBe(200)
    expect(result.urlsCount).toBe(1)
  })

  it('handles successful API response (HTTP 200/202) and passes signal', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      status: 200,
      statusText: 'OK',
    })

    const customSignal = new AbortController().signal
    const urls = ['https://thor.trinadhthatakula.com/']
    const result = await submitIndexNow({ urls, fetchFn: mockFetch, signal: customSignal })

    expect(mockFetch).toHaveBeenCalledTimes(1)
    const [endpoint, req] = mockFetch.mock.calls[0]
    expect(endpoint).toBe('https://api.indexnow.org/indexnow')
    expect(req.method).toBe('POST')
    expect(req.headers['Content-Type']).toContain('application/json')
    expect(req.signal).toBe(customSignal)

    const body = JSON.parse(req.body)
    expect(body.key).toBe(INDEXNOW.key)
    expect(body.urlList).toEqual(urls)

    expect(result.ok).toBe(true)
    expect(result.status).toBe(200)
  })

  it('fails gracefully when key file is missing in target directory', () => {
    const check = verifyKeyFile(join(WEB_DIR, 'nonexistent_dir'), INDEXNOW.key)
    expect(check.ok).toBe(false)
    expect(check.error).toContain('Key file not found')
  })

  it('handles API rejection or failure gracefully', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      status: 422,
      statusText: 'Unprocessable Entity',
    })

    const urls = ['https://thor.trinadhthatakula.com/']
    const result = await submitIndexNow({ urls, fetchFn: mockFetch })

    expect(result.ok).toBe(false)
    expect(result.status).toBe(422)
  })
})

import { describe, expect, it } from 'vitest'
import { parse as parseToml } from 'smol-toml'
import {
  RepoFactsError,
  androidNameForApi,
  deriveVersionName,
  parseCatalogVersions,
  parseJavaProperties,
  readSdkInt,
  readSemver,
  resolveVersionCode,
} from './parse.ts'

describe('deriveVersionName', () => {
  // Pinned to values fixed by CLAUDE.md's documented scheme, not by whatever
  // gradle.properties happens to hold today. A release must never redden this.
  it.each([
    [1900, '1.90.0'],
    [1822, '1.82.2'],
    [1908, '1.90.8'],
    [1712, '1.71.2'],
    [1819, '1.81.9'],
    [1931, '1.93.1'],
    [2000, '2.0.0'],
    [10000, '10.0.0'],
  ])('%i -> %s', (code, name) => {
    expect(deriveVersionName(code)).toBe(name)
  })

  it('rejects a code below 1000', () => {
    expect(() => deriveVersionName(999)).toThrow(RepoFactsError)
  })

  it('rejects a non-integer', () => {
    expect(() => deriveVersionName(1931.5)).toThrow(RepoFactsError)
  })
})

describe('parseJavaProperties', () => {
  it('reads simple key=value pairs', () => {
    expect(parseJavaProperties('a=1\nb=2\n')).toEqual({ a: '1', b: '2' })
  })

  it('ignores # and ! comment lines', () => {
    expect(parseJavaProperties('# c\n! d\na=1\n')).toEqual({ a: '1' })
  })

  it('accepts a colon separator', () => {
    expect(parseJavaProperties('key : value\n')).toEqual({ key: 'value' })
  })

  it('accepts bare whitespace as a separator', () => {
    expect(parseJavaProperties('key value\n')).toEqual({ key: 'value' })
  })

  it('handles CRLF', () => {
    expect(parseJavaProperties('a=1\r\nb=2\r\n')).toEqual({ a: '1', b: '2' })
  })

  it('trims surrounding whitespace on the key and the left of the value', () => {
    expect(parseJavaProperties('  a  =  1\n')).toEqual({ a: '1' })
  })

  it('lets the last duplicate key win, as java.util.Properties does', () => {
    expect(parseJavaProperties('a=1\na=2\n')).toEqual({ a: '2' })
  })

  it('joins backslash line continuations', () => {
    expect(parseJavaProperties('a=one \\\ntwo\n')).toEqual({ a: 'one two' })
  })
})

describe('resolveVersionCode', () => {
  it('prefers versionCode over initialVersionCode regardless of file order', () => {
    // THE regression test. In the real gradle.properties `initialVersionCode=1921`
    // sits four lines ABOVE `versionCode=1931`. A first-match, substring or
    // case-insensitive lookup returns 1921 and the site prints "1.92.1" for a
    // 1.93.1 build — a wrong fact with a plausible shape, which is the worst kind.
    const props = parseJavaProperties(
      ['# comment', 'initialVersionCode=1921', '', 'versionCode=1931', ''].join('\n'),
    )
    expect(resolveVersionCode(props)).toBe(1931)
    expect(deriveVersionName(resolveVersionCode(props))).toBe('1.93.1')
  })

  it('falls back to initialVersionCode when versionCode is absent', () => {
    expect(resolveVersionCode({ initialVersionCode: '1921' })).toBe(1921)
  })

  it('throws, naming the file and key, when neither is set', () => {
    expect(() => resolveVersionCode({})).toThrow(/gradle\.properties.*versionCode/s)
  })

  it('throws on a non-numeric value', () => {
    expect(() => resolveVersionCode({ versionCode: '1.93.1' })).toThrow(/not a positive integer/)
  })

  it('throws on a code below 1000, which would derive "0.99.9"', () => {
    expect(() => resolveVersionCode({ versionCode: '999' })).toThrow(/below 1000/)
  })

  it('throws on a negative code', () => {
    expect(() => resolveVersionCode({ versionCode: '-1' })).toThrow(/not a positive integer/)
  })
})

describe('parseCatalogVersions', () => {
  // Eight bare keys already collide between [versions] and other tables in the
  // real catalog. A whole-file regex returns whichever it hits first.
  const catalog = [
    '[versions]',
    'compileSdk = "37"',
    'targetSdk = "37"',
    'minSdk = "28"',
    'room = "2.9.0"',
    'thorExtensionApi = "3.0.0"',
    '',
    '[libraries]',
    'room = { module = "androidx.room:room-runtime", version.ref = "room" }',
    '',
    '[plugins]',
    'room = { id = "androidx.room", version.ref = "room" }',
  ].join('\n')

  it('reads only the [versions] table', () => {
    const v = parseCatalogVersions(catalog, parseToml)
    expect(v.room).toBe('2.9.0')
    expect(v.compileSdk).toBe('37')
  })

  it('throws when [versions] is missing', () => {
    expect(() => parseCatalogVersions('[libraries]\n', parseToml)).toThrow(/no `\[versions\]` table/)
  })

  it('throws on invalid TOML', () => {
    expect(() => parseCatalogVersions('[versions\n', parseToml)).toThrow(/not valid TOML/)
  })

  describe('readSdkInt', () => {
    it('returns a number', () => {
      expect(readSdkInt(parseCatalogVersions(catalog, parseToml), 'minSdk')).toBe(28)
    })

    it('throws when the key is missing', () => {
      expect(() => readSdkInt({}, 'minSdk')).toThrow(/\[versions\] minSdk` is missing/)
    })

    it('throws on a non-integer', () => {
      expect(() => readSdkInt({ minSdk: '28.0' }, 'minSdk')).toThrow(/not a bare integer/)
    })

    it('throws on an implausible level', () => {
      expect(() => readSdkInt({ minSdk: '280' }, 'minSdk')).toThrow(/plausible 1\.\.99 range/)
    })
  })

  describe('readSemver', () => {
    it('returns the string, not a digit-stripped number', () => {
      // The repo's `tr -dc '0-9'` idiom turns "3.0.0" into 300, which as a
      // dependency coordinate does not resolve.
      const v = readSemver(parseCatalogVersions(catalog, parseToml), 'thorExtensionApi')
      expect(v).toBe('3.0.0')
      expect(v).not.toBe('300')
    })

    it('accepts a pre-release suffix', () => {
      expect(readSemver({ x: '3.0.0-rc.1' }, 'x')).toBe('3.0.0-rc.1')
    })

    it('throws on a non-semver value', () => {
      expect(() => readSemver({ x: '3.0' }, 'x')).toThrow(/not a semantic version/)
    })
  })
})

describe('androidNameForApi', () => {
  it('maps 28 to Android 9', () => {
    expect(androidNameForApi(28)).toBe('Android 9')
  })

  it('throws rather than guessing a name for an unmapped level', () => {
    // API 37 has no settled public name. Inventing one is exactly the
    // confidently-wrong prose this module exists to prevent.
    expect(() => androidNameForApi(37)).toThrow(/do not guess one into the page/)
  })
})

/**
 * Loading a built site off disk, and the glob matcher the claims rules use to
 * scope themselves to particular routes.
 *
 * Every checker in this directory takes a *directory* argument rather than
 * hardcoding `dist`. That is not configurability for its own sake: `dist` does
 * not exist until `astro build` has run, so the only way to test these checkers
 * is to point them at a fixture tree. Sharing one loader means the fixtures
 * exercise the same code path the deploy does.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

/**
 * Directories that are never part of the served site.
 *
 * `_astro` is deliberately **not** here. It holds no HTML, so skipping it would
 * cost `loadPages` nothing — but {@link fileIndex} shares this walk, and every
 * page links its stylesheet and preloads its fonts out of `_astro`. Skipping it
 * made the link checker report all eight of those as broken, on every page, on a
 * build where the files were present. That is the false-fail the whole fixture
 * suite exists to prevent: a gate that cries wolf on correct output gets deleted,
 * and then the real broken links ship.
 */
const SKIP_DIRS = new Set(['node_modules', '.git'])

/**
 * Every file under `root`, as absolute paths, depth-first and sorted so a
 * failure list is stable between runs.
 */
export function walkFiles(root) {
  const out = []
  const visit = (dir) => {
    let entries
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch (error) {
      if (error.code === 'ENOENT') return
      throw error
    }
    for (const entry of entries.sort((a, b) => (a.name < b.name ? -1 : 1))) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (SKIP_DIRS.has(entry.name)) continue
        visit(full)
      } else if (entry.isFile()) {
        out.push(full)
      }
    }
  }
  visit(root)
  return out
}

/** `dist/faq/index.html` -> `faq/index.html`, with forward slashes on Windows too. */
export function relPosix(root, file) {
  return relative(root, file).split(sep).join('/')
}

/** True when `p` names an existing file (not a directory). */
export function isFile(p) {
  try {
    return statSync(p).isFile()
  } catch {
    return false
  }
}

/**
 * Load every HTML page under `root`.
 *
 * Returns `{ file, rel, html }` records. The `.html` filter is what keeps hashed
 * assets out of the page count; no directory needs excluding to achieve it.
 */
export function loadPages(root) {
  return walkFiles(root)
    .filter((f) => f.endsWith('.html'))
    .map((file) => ({ file, rel: relPosix(root, file), html: readFileSync(file, 'utf8') }))
}

/** Every file in the tree as a Set of dist-relative posix paths — the link checker's index. */
export function fileIndex(root) {
  return new Set(walkFiles(root).map((f) => relPosix(root, f)))
}

/**
 * A deliberately small glob: `*` (within one segment), `**` (across segments),
 * `?`, and `{a,b}` alternation. No negation, no character classes, no nesting.
 *
 * A dependency is not worth it for a handful of `appliesTo` patterns, and a
 * hand-written matcher is one that a reviewer can hold in their head — which
 * matters more here than power, because a glob that matches nothing turns its
 * rule off silently.
 */
export function globToRegExp(pattern) {
  if (/\{[^}]*\{/.test(pattern)) {
    throw new Error(`Nested {} in glob "${pattern}" is not supported.`)
  }
  let out = '^'
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i]
    if (c === '*') {
      if (pattern[i + 1] === '*') {
        i++
        // `**/` may match zero segments, so `**/*.html` also matches `index.html`.
        if (pattern[i + 1] === '/') {
          i++
          out += '(?:.*/)?'
        } else {
          out += '.*'
        }
      } else {
        out += '[^/]*'
      }
    } else if (c === '?') {
      out += '[^/]'
    } else if (c === '{') {
      const close = pattern.indexOf('}', i)
      if (close === -1) throw new Error(`Unclosed { in glob "${pattern}".`)
      const alts = pattern
        .slice(i + 1, close)
        .split(',')
        .map((a) => a.replace(/[.+^$()|[\]\\]/g, '\\$&').replace(/\*/g, '[^/]*'))
      out += `(?:${alts.join('|')})`
      i = close
    } else {
      out += c.replace(/[.+^$(){}|[\]\\]/g, '\\$&')
    }
  }
  return new RegExp(`${out}$`)
}

export function matchGlob(pattern, value) {
  return globToRegExp(pattern).test(value)
}

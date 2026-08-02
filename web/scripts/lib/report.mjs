/**
 * Shared CLI shell for the correctness gates: argument handling, the counts
 * line, and the exit code.
 *
 * The counts line is not decoration. A checker that matched zero files exits 0,
 * looks identical to a passing run, and stays that way forever — the single most
 * dangerous failure mode in this whole phase. Printing "3 pages, 12 links" on
 * every run is what makes "0 pages" visible to a human reading a build log, and
 * {@link emptyScanFailure} is what makes it visible to the build.
 */
import { pathToFileURL } from 'node:url'

/** True when this module was the process entry point rather than an import. */
export function isMain(metaUrl) {
  const entry = process.argv[1]
  return Boolean(entry) && pathToFileURL(entry).href === metaUrl
}

/** The directory to scan. `package.json` passes `dist`; the tests pass a fixture. */
export function dirArg(fallback = 'dist') {
  return process.argv[2] ?? fallback
}

/**
 * The failure every checker shares: the glob matched nothing. Phrased as a
 * failure rather than a warning because the honest reading of "no pages" is
 * "the check ran before `astro build`", and a gate that passes in that state is
 * not a gate.
 */
export function emptyScanFailure(dir) {
  return {
    where: dir,
    what: 'no HTML pages found',
    why:
      `Nothing under "${dir}" matched *.html. Either the directory is wrong or the check ran ` +
      'before `astro build`. Refusing to pass: a checker that silently scanned zero files is ' +
      'indistinguishable from one that found no problems.',
  }
}

/**
 * Print the result and return a process exit code.
 *
 * @param name   the script's name, used as the log prefix
 * @param counts ordered `[label, value]` pairs, printed on success and failure alike
 * @param failures `{ where, what, why }` records; `where` is the source file or page
 */
export function report(name, counts, failures) {
  const summary = counts.map(([label, value]) => `${value} ${label}`).join(', ')

  if (failures.length === 0) {
    process.stdout.write(`${name}: OK — ${summary}\n`)
    return 0
  }

  process.stderr.write(`${name}: FAILED — ${failures.length} problem(s); ${summary}\n\n`)
  let lastWhere = null
  for (const failure of failures) {
    if (failure.where !== lastWhere) {
      process.stderr.write(`  ${failure.where}\n`)
      lastWhere = failure.where
    }
    process.stderr.write(`    ${failure.what}\n`)
    for (const line of String(failure.why).split('\n')) {
      process.stderr.write(`      ${line}\n`)
    }
  }
  process.stderr.write('\n')
  return 1
}

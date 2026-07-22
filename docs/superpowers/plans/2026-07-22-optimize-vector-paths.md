# VectorDrawable Path Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shrink the `pathData` of the three lint `VectorPath`-flagged drawables (`dhizuku.xml`, `thor_drawn_foreground.xml`, `thor_mono.xml`) via strictly value-preserving string compaction, proven pixel-identical before/after.

**Architecture:** A single Node harness (`build/vecopt/harness.js`, gitignored, ephemeral) parses each VectorDrawable XML, tokenizes each `pathData` into canonical command/operand segments, re-emits them in a minimal textual form (never changing a numeric value), and self-verifies by re-parsing the output and comparing every operand with `parseFloat` equality. It then converts each drawable to SVG, renders before/after with `rsvg-convert`, and gates on `magick compare -metric AE == 0` at multiple sizes/backgrounds, emitting a `[before | after | diff]` composite PNG for human sign-off. The harness runs inside `ctx_execute` (node, via `child_process`); the optimized `pathData` is written back to the `.xml` only via the Edit tool, only after `AE == 0` and visual sign-off.

**Tech Stack:** Node.js built-ins (`fs`, `child_process`) run through `ctx_execute`; `rsvg-convert` (librsvg 2.62.3, cairo); ImageMagick 7.1.2 (`compare`, `+append`); Android lint via `ctx_execute` (shell).

## Global Constraints

- **Pixel-identical / lossless only:** no rounding, no precision reduction; every coordinate's numeric value is preserved exactly — only its textual encoding changes.
- **Transform set (verbatim):** leading-zero strip (`0.5`→`.5`), trailing-zero/bare-decimal strip (`1.230`→`1.23`, `2.0`→`2`), negative-zero normalize (`-0`→`0`), sign-as-separator (`.14 -.1`→`.14-.1`), implicit repeated command (`L1 2 L3 4`→`L1 2 3 4`, never merged across a moveto).
- **Excluded:** implicit-decimal separator (`.5.5`) is OFF; no no-op removal, no path merging, no arc rewriting.
- **Command case (`M`/`m`, `L`/`l`, `C`/`c`, …) preserved verbatim** — absolute vs relative is never changed.
- **Only these three files** under `app/src/main/res/drawable/`: `dhizuku.xml`, `thor_drawn_foreground.xml`, `thor_mono.xml`. No other drawable is touched.
- **The `VectorPath` lint warning is EXPECTED to remain** on all three (they stay >800 chars). That is not a failure; do not chase it.
- **Gate (hard):** `magick compare -metric AE` must equal `0` at every render size/background, per file, AND the harness value-preservation self-check must pass, BEFORE any `.xml` edit. Any non-zero AE rejects that file's change.
- **Human gate:** each file requires visual sign-off on its `[before | after | diff]` composite before the edit is applied.
- **No version bump:** resource-only, non-release change on `dev`. Leave `gradle.properties versionCode` untouched.
- **Tooling boundaries:** optimizer/converter/render run in `ctx_execute`; the harness file and `.xml` edits are created via the Write/Edit tools (never via shell/ctx_execute); the composite PNG is viewed via the Read tool; gradle/lint run via `ctx_execute` (shell). Scratch lives in `build/vecopt/` (matched by `.gitignore` `/build`), is never `git add`ed, and is removed in the final task.
- **Git:** commit only when a task says to; commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; PR body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`; push only to GitHub `origin` (never the codeberg mirror).
- **Branch:** all work on `chore/optimize-vector-paths` (already created off `dev`; the spec is already committed there as `ffb2001`).

---

### Task 1: Build and self-verify the path optimizer harness

**Files:**
- Create: `build/vecopt/harness.js` (gitignored scratch tooling — never committed)

**Interfaces:**
- Produces (functions later tasks rely on, all in `harness.js`, selected by `argv[2]` mode):
  - `parseVd(xml) -> {viewportWidth, viewportHeight, group|null, paths:[{fillColor, fillType, pathData}]}`
  - `optimize(pathData) -> {out:String, valuePreserved:Boolean, why:String|undefined, beforeLen:Int, afterLen:Int}`
  - `buildSvg(vd, useOptimized:Boolean, optimizedData:String[], sizePx:Int) -> String`
  - `render(svg, sizePx, bg:String|null, outPng)`, `ae(png1, png2, diffPng|null) -> Number`
  - CLI modes: `node harness.js selftest`, `node harness.js check <file.xml>`, `node harness.js gate <file.xml>`
  - `gate` mode prints JSON `{file, allPreserved, checks:[{i,beforeLen,afterLen,saved,valuePreserved,why}], aeMatrix:[{size,bg,ae}], gatePass:Boolean, reviewPng:String, optimizedData:String[]}`

- [ ] **Step 1: Write the harness**

Write `build/vecopt/harness.js` with exactly this content:

```javascript
'use strict';
const fs = require('fs');
const { execFileSync } = require('child_process');

const ROOT = '/Users/trinadhthatakula/StudioProjects/Thor';
const SCRATCH = ROOT + '/build/vecopt';
const DRAWABLE = ROOT + '/app/src/main/res/drawable';

// ---------- VectorDrawable XML parse (these files are simple & well-formed) ----------
function parseVd(xml) {
  const vp = s => { const m = xml.match(new RegExp('android:' + s + '="([\\d.]+)"')); return m ? m[1] : null; };
  let group = null;
  const gm = xml.match(/<group\b([\s\S]*?)>/);
  if (gm) {
    const g = gm[1];
    const a = k => { const m = g.match(new RegExp('android:' + k + '="(-?[\\d.]+)"')); return m ? m[1] : null; };
    group = { scaleX: a('scaleX'), scaleY: a('scaleY'), translateX: a('translateX'), translateY: a('translateY') };
  }
  const paths = [];
  const pre = /<path\b([\s\S]*?)\/>/g; let pm;
  while ((pm = pre.exec(xml))) {
    const body = pm[1];
    const a = k => { const m = body.match(new RegExp('android:' + k + '="([^"]*)"')); return m ? m[1] : null; };
    paths.push({ fillColor: a('fillColor'), fillType: a('fillType'), pathData: a('pathData') });
  }
  return { viewportWidth: vp('viewportWidth'), viewportHeight: vp('viewportHeight'), group, paths };
}

// ---------- path tokenizer + canonical segmentation ----------
const ARITY = { M: 2, L: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, T: 2, A: 7, Z: 0 };
const CMD = 'MmLlHhVvCcSsQqTtAaZz';
function tokenize(d) {
  const toks = []; let i = 0;
  const numRe = /-?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/y;
  while (i < d.length) {
    const c = d[i];
    if (CMD.includes(c)) { toks.push({ t: 'c', v: c }); i++; continue; }
    if (c === ' ' || c === ',' || c === '\t' || c === '\n' || c === '\r') { i++; continue; }
    numRe.lastIndex = i;
    const m = numRe.exec(d);
    if (m && m.index === i) { toks.push({ t: 'n', v: m[0] }); i = numRe.lastIndex; continue; }
    throw new Error('unexpected char @' + i + ': ' + JSON.stringify(c));
  }
  return toks;
}
function toSegments(toks) {
  const segs = []; let i = 0; let cmd = null;
  while (i < toks.length) {
    if (toks[i].t === 'c') { cmd = toks[i].v; i++; if (cmd === 'Z' || cmd === 'z') { segs.push({ cmd, nums: [] }); continue; } }
    if (cmd == null) throw new Error('number before command');
    if (cmd === 'A' || cmd === 'a') throw new Error('arc command unsupported');
    const n = ARITY[cmd.toUpperCase()];
    const nums = [];
    for (let k = 0; k < n; k++) { if (i >= toks.length || toks[i].t !== 'n') throw new Error('missing operand for ' + cmd); nums.push(toks[i].v); i++; }
    segs.push({ cmd, nums });
    if (cmd === 'M') cmd = 'L'; else if (cmd === 'm') cmd = 'l';
  }
  return segs;
}

// ---------- minimal numeric formatting (pure string; value-preserving) ----------
function compactNum(s) {
  if (/[eE]/.test(s)) return s;
  const neg = s[0] === '-';
  let body = neg ? s.slice(1) : s;
  const dot = body.indexOf('.');
  let intp, frac;
  if (dot >= 0) { intp = body.slice(0, dot); frac = body.slice(dot + 1).replace(/0+$/, ''); }
  else { intp = body; frac = ''; }
  intp = intp.replace(/^0+/, '');
  let out = frac ? (intp + '.' + frac) : (intp || '0');
  if (out === '') out = '0';
  if (parseFloat((neg ? '-' : '') + out) === 0) return '0';
  return (neg ? '-' : '') + out;
}

// ---------- re-emit compacted path ----------
function emitPath(segs) {
  let out = ''; let prev = null;
  for (const s of segs) {
    const merge = prev !== null && s.cmd === prev && s.cmd !== 'M' && s.cmd !== 'm' && s.cmd !== 'Z' && s.cmd !== 'z';
    if (!merge) { out += s.cmd; prev = s.cmd; }
    for (const raw of s.nums) {
      const num = compactNum(raw);
      const endsWithCmd = /[MmLlHhVvCcSsQqTtAaZz]$/.test(out);
      const sep = (out.length > 0 && !endsWithCmd && num[0] !== '-') ? ' ' : '';
      out += sep + num;
    }
  }
  return out;
}

function segEqual(a, b) {
  if (a.length !== b.length) return { ok: false, why: 'segcount ' + a.length + ' vs ' + b.length };
  for (let i = 0; i < a.length; i++) {
    if (a[i].cmd !== b[i].cmd) return { ok: false, why: 'cmd@' + i + ' ' + a[i].cmd + '/' + b[i].cmd };
    if (a[i].nums.length !== b[i].nums.length) return { ok: false, why: 'arity@' + i };
    for (let k = 0; k < a[i].nums.length; k++) {
      const x = parseFloat(a[i].nums[k]), y = parseFloat(b[i].nums[k]);
      if (x !== y) return { ok: false, why: 'val@' + i + ':' + k + ' ' + x + '/' + y };
    }
  }
  return { ok: true };
}
function optimize(pathData) {
  const segs = toSegments(tokenize(pathData));
  const out = emitPath(segs);
  const chk = segEqual(segs, toSegments(tokenize(out)));
  return { out, valuePreserved: chk.ok, why: chk.why, beforeLen: pathData.length, afterLen: out.length };
}

// ---------- VD -> SVG + render + diff ----------
function buildSvg(vd, useOpt, optData, sizePx) {
  const inner = vd.paths.map((p, idx) => {
    const d = useOpt ? optData[idx] : p.pathData;
    let attr = 'd="' + d + '" fill="' + (p.fillColor || '#000000') + '"';
    if (p.fillType === 'evenOdd') attr += ' fill-rule="evenodd"';
    return '<path ' + attr + '/>';
  }).join('');
  let content = inner;
  const g = vd.group;
  if (g && g.scaleX) content = '<g transform="translate(' + (g.translateX || '0') + ',' + (g.translateY || '0') + ') scale(' + g.scaleX + ',' + g.scaleY + ')">' + inner + '</g>';
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + vd.viewportWidth + ' ' + vd.viewportHeight + '" width="' + sizePx + '" height="' + sizePx + '">' + content + '</svg>';
}
function render(svg, sizePx, bg, outPng) {
  const args = ['-w', String(sizePx), '-h', String(sizePx), '-o', outPng];
  if (bg) args.unshift('--background-color', bg);
  execFileSync('rsvg-convert', args, { input: svg });
}
function ae(p1, p2, diff) {
  try { execFileSync('magick', ['compare', '-metric', 'AE', p1, p2, diff || 'null:'], { stdio: ['ignore', 'ignore', 'pipe'] }); return 0; }
  catch (e) { const s = (e.stderr ? e.stderr.toString() : '').trim(); const m = s.match(/^([\d.eE+]+)/); return m ? parseFloat(m[1]) : NaN; }
}

// ---------- CLI ----------
function selftest() {
  const cases = [['0.501875', '.501875'], ['-0.5', '-.5'], ['2.0', '2'], ['2.00', '2'], ['-0', '0'], ['162.96', '162.96'], ['0', '0'], ['.5', '.5'], ['1.230', '1.23']];
  for (const [inp, exp] of cases) { const got = compactNum(inp); if (got !== exp) throw new Error('compactNum(' + inp + ')=' + got + ' expected ' + exp); if (parseFloat(inp) !== parseFloat(got)) throw new Error('value drift ' + inp); }
  // teeth: a rounding transform MUST be rejected by segEqual
  const segs = toSegments(tokenize('M0.501875,2 L-0.5,.25'));
  const rounded = segs.map(s => ({ cmd: s.cmd, nums: s.nums.map(n => (Math.round(parseFloat(n) * 10) / 10).toString()) }));
  if (segEqual(segs, rounded).ok) throw new Error('segEqual failed to catch rounding');
  // real optimize preserves + reparses identically
  const o = optimize('M0.501875,2.00 -0.0,.5 L-1.15,1.37');
  if (!o.valuePreserved) throw new Error('real optimize not preserved: ' + o.why);
  console.log('SELFTEST OK');
}
function check(file) {
  const vd = parseVd(fs.readFileSync(DRAWABLE + '/' + file, 'utf8'));
  const checks = vd.paths.map((p, i) => { const o = optimize(p.pathData); return { i, beforeLen: o.beforeLen, afterLen: o.afterLen, saved: o.beforeLen - o.afterLen, valuePreserved: o.valuePreserved, why: o.why }; });
  console.log(JSON.stringify({ file, allPreserved: checks.every(c => c.valuePreserved), checks }, null, 2));
}
function gate(file) {
  const vd = parseVd(fs.readFileSync(DRAWABLE + '/' + file, 'utf8'));
  const opt = vd.paths.map(p => optimize(p.pathData));
  const optData = opt.map(o => o.out);
  const allPreserved = opt.every(o => o.valuePreserved);
  const aeMatrix = [];
  for (const size of [96, 512, 2048]) for (const bg of [null, '#808080']) {
    const tag = size + '.' + (bg ? 'g' : 't');
    const b = SCRATCH + '/' + file + '.before.' + tag + '.png', a = SCRATCH + '/' + file + '.after.' + tag + '.png';
    render(buildSvg(vd, false, null, size), size, bg, b);
    render(buildSvg(vd, true, optData, size), size, bg, a);
    aeMatrix.push({ size, bg: bg || 'transparent', ae: ae(b, a) });
  }
  const rb = SCRATCH + '/rev_before.png', ra = SCRATCH + '/rev_after.png', rd = SCRATCH + '/rev_diff.png';
  render(buildSvg(vd, false, null, 512), 512, '#808080', rb);
  render(buildSvg(vd, true, optData, 512), 512, '#808080', ra);
  ae(rb, ra, rd);
  const reviewPng = SCRATCH + '/review_' + file + '.png';
  execFileSync('magick', [rb, ra, rd, '+append', reviewPng]);
  console.log(JSON.stringify({ file, allPreserved, checks: opt.map((o, i) => ({ i, beforeLen: o.beforeLen, afterLen: o.afterLen, saved: o.beforeLen - o.afterLen, valuePreserved: o.valuePreserved, why: o.why })), aeMatrix, gatePass: allPreserved && aeMatrix.every(r => r.ae === 0), reviewPng, optimizedData: optData }, null, 2));
}
const mode = process.argv[2], arg = process.argv[3];
try { if (mode === 'selftest') selftest(); else if (mode === 'check') check(arg); else if (mode === 'gate') gate(arg); else throw new Error('usage: selftest|check <f>|gate <f>'); }
catch (e) { console.log('ERROR: ' + e.message); process.exitCode = 1; }
```

- [ ] **Step 2: Run the self-test (must prove the checker has teeth)**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js selftest`
Expected stdout: `SELFTEST OK` (asserts `compactNum` is value-preserving on the battery, that `segEqual` REJECTS a rounding transform, and that a real `optimize` round-trips). If it prints `ERROR: …`, fix the harness before continuing.

- [ ] **Step 3: Run `check` on all three files**

Run via `ctx_execute` (shell), one call:
```
node build/vecopt/harness.js check dhizuku.xml
node build/vecopt/harness.js check thor_drawn_foreground.xml
node build/vecopt/harness.js check thor_mono.xml
```
Expected: each prints `"allPreserved": true` and every path's `"saved" > 0` (dhizuku small; `thor_drawn_foreground` and `thor_mono` larger). If any `valuePreserved` is false, the `why` field localizes the bug — fix `harness.js` and re-run. No commit (harness is gitignored tooling).

---

### Task 2: Gate and apply `dhizuku.xml` (1 path, no group)

**Files:**
- Modify: `app/src/main/res/drawable/dhizuku.xml` (the single `android:pathData` value)

**Interfaces:**
- Consumes: `harness.js gate` from Task 1.

- [ ] **Step 1: Run the render/diff gate**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js gate dhizuku.xml`
Expected JSON: `"allPreserved": true`, every `aeMatrix[].ae` is `0`, `"gatePass": true`. Note the single `optimizedData[0]` string and the `reviewPng` path.
**If `gatePass` is not true, STOP** — do not edit the file; investigate (a non-zero AE means the optimizer changed rendered geometry).

- [ ] **Step 2: Human visual sign-off**

Read the composite with the Read tool: `build/vecopt/review_dhizuku.xml.png` (a `[before | after | diff]` strip on gray; the diff panel should show no red). Present it to the user and get explicit confirmation the icon is unchanged. Do not proceed without sign-off.

- [ ] **Step 3: Apply the optimized `pathData`**

Using the Edit tool on `app/src/main/res/drawable/dhizuku.xml`, replace the entire original `android:pathData="…"` value with the `optimizedData[0]` string from Step 1. Change nothing else (fillColor, dimensions, viewport untouched).

- [ ] **Step 4: Re-gate the committed file to confirm the edit is faithful**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js gate dhizuku.xml`
Expected: `"gatePass": true` and now every path's `"saved"` is `0` (the file already holds the optimized form → re-optimizing is a no-op). This proves the Edit wrote exactly the optimized string.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/dhizuku.xml
git commit -m "$(cat <<'EOF'
perf(res): losslessly compact dhizuku.xml pathData

Value-preserving string compaction (leading/trailing-zero strip,
sign-as-separator, implicit repeated command). Verified pixel-identical
via rsvg-convert render + ImageMagick AE==0 at 96/512/2048px on
transparent and solid backgrounds.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Gate and apply `thor_drawn_foreground.xml` (1 path inside a `<group>`)

**Files:**
- Modify: `app/src/main/res/drawable/thor_drawn_foreground.xml` (the single `android:pathData` value)

**Interfaces:**
- Consumes: `harness.js gate` (exercises the `<group>` `translate`+`scale` → SVG `transform` path in `buildSvg`).

- [ ] **Step 1: Run the render/diff gate**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js gate thor_drawn_foreground.xml`
Expected: `"allPreserved": true`, all `aeMatrix[].ae === 0`, `"gatePass": true`. Note `optimizedData[0]` and `reviewPng`.
**If `gatePass` is not true, STOP** and investigate.

- [ ] **Step 2: Human visual sign-off**

Read `build/vecopt/review_thor_drawn_foreground.xml.png` with the Read tool. Because this drawable's fill is white, the gray background makes the Thor mark visible; confirm before == after and the diff panel is clean. Present to the user; get explicit sign-off.

- [ ] **Step 3: Apply the optimized `pathData`**

Using the Edit tool on `app/src/main/res/drawable/thor_drawn_foreground.xml`, replace the original `android:pathData="…"` value inside the `<group>`'s `<path>` with `optimizedData[0]`. Leave the `<group>` transform attributes and all stroke attributes unchanged.

- [ ] **Step 4: Re-gate to confirm the edit is faithful**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js gate thor_drawn_foreground.xml`
Expected: `"gatePass": true`, path `"saved"` now `0`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/thor_drawn_foreground.xml
git commit -m "$(cat <<'EOF'
perf(res): losslessly compact thor_drawn_foreground.xml pathData

Value-preserving string compaction; group transform preserved. Verified
pixel-identical via rsvg-convert render + ImageMagick AE==0 at
96/512/2048px on transparent and solid backgrounds.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Gate and apply `thor_mono.xml` (3 paths)

**Files:**
- Modify: `app/src/main/res/drawable/thor_mono.xml` (all three `android:pathData` values)

**Interfaces:**
- Consumes: `harness.js gate` (returns `optimizedData` as a 3-element array, one per `<path>`, in document order).

- [ ] **Step 1: Run the render/diff gate**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js gate thor_mono.xml`
Expected: `"allPreserved": true`, `checks` has 3 entries all with `"saved" > 0`, all `aeMatrix[].ae === 0`, `"gatePass": true`. The whole drawable (all 3 paths) is rendered together, so `AE == 0` covers all three at once. Note `optimizedData[0..2]` and `reviewPng`.
**If `gatePass` is not true, STOP** and investigate.

- [ ] **Step 2: Human visual sign-off**

Read `build/vecopt/review_thor_mono.xml.png` with the Read tool (white fills on gray). Confirm the composed mono icon is unchanged and the diff panel is clean. Present to the user; get explicit sign-off.

- [ ] **Step 3: Apply the optimized `pathData` (three edits)**

Using the Edit tool on `app/src/main/res/drawable/thor_mono.xml`, replace each of the three `android:pathData` values with the corresponding `optimizedData[0]`, `optimizedData[1]`, `optimizedData[2]` (document order matches the `<path>` order: lines 8, 13, 18 in the original). Each original `pathData` string is unique, so three targeted Edits are unambiguous. Leave `fillColor` and stroke attributes unchanged.

- [ ] **Step 4: Re-gate to confirm the edits are faithful**

Run via `ctx_execute` (shell): `node build/vecopt/harness.js gate thor_mono.xml`
Expected: `"gatePass": true`, all three `"saved"` now `0`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/thor_mono.xml
git commit -m "$(cat <<'EOF'
perf(res): losslessly compact thor_mono.xml pathData (3 paths)

Value-preserving string compaction across all three paths. Verified
pixel-identical via rsvg-convert render of the composed drawable +
ImageMagick AE==0 at 96/512/2048px on transparent and solid backgrounds.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Measure, clean up, and open the PR

**Files:**
- None modified (measurement + housekeeping).

- [ ] **Step 1: Record byte savings**

Run via `ctx_execute` (shell), one call, and record the totals in the PR body:
```
for f in dhizuku.xml thor_drawn_foreground.xml thor_mono.xml; do node build/vecopt/harness.js check "$f"; done
```
(After Tasks 2–4 the applied files re-optimize to `saved: 0`; capture the *original* savings from the Task 2–4 Step 1 gate outputs instead — sum of all paths' `saved`.)

- [ ] **Step 2: Run lint to confirm no regressions and record the `VectorPath` count**

Run via `ctx_execute` (shell), long timeout: `./gradlew :app:lintFossRelease`
Then inspect the report for `VectorPath`:
```
grep -c 'VectorPath' app/build/reports/lint-results-fossRelease.txt 2>/dev/null || echo "check app/build/reports/ for the lint result file"
```
Expected: build succeeds; `VectorPath` still present on the three files (accepted per Global Constraints) and **no new** lint issues introduced. If lint surfaces a *new* error/warning attributable to these edits, STOP and investigate.

- [ ] **Step 3: Remove scratch**

```bash
rm -rf build/vecopt
git status --short
```
Expected: `git status` shows a clean tree (the three drawables already committed; `build/vecopt` was never tracked — confirm it does not appear).

- [ ] **Step 4: Push and open the PR to `dev`**

```bash
git push -u origin chore/optimize-vector-paths
```
Then open the PR with `gh pr create --base dev`, titled `perf(res): losslessly compact the three long VectorPath drawables`, body summarizing: the three files, the per-file byte savings from Step 1, the verification method (rsvg-convert render + `AE==0` at 96/512/2048px on transparent+gray + human sign-off), and the explicit note that the `VectorPath` warning intentionally remains (lossless bar). End the PR body with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. Push only to GitHub `origin`.

- [ ] **Step 5: Pre-PR adversarial review (ultracode)**

Before requesting merge, run a short adversarial review (Workflow: dimensions → find → verify) over the three committed `.xml` diffs, checking specifically: (a) no numeric value changed (spot-check a sample of coordinates against the originals), (b) command letters/case unchanged, (c) fill/stroke/viewport/group attributes untouched, (d) the edit replaced only `pathData`. Report only confirmed findings; fix any before merge. The on-device smoke test remains the user's final runtime check.

---

## Self-Review

**Spec coverage:**
- Lossless string compaction with the exact transform set → Task 1 (`compactNum`, `emitPath`), Global Constraints. ✓
- `.5.5` excluded, command case preserved, negative-zero normalized → `emitPath`/`compactNum` (no implicit-decimal emitted; letters copied verbatim; `-0`→`0`) + selftest. ✓
- VD→SVG (viewport→viewBox, group transform, fill, fill-rule) → `buildSvg`, Task 3 exercises the group. ✓
- Render at 96/512/2048 on transparent+solid, `AE==0` gate → `gate` mode `aeMatrix`, Tasks 2–4 Step 1. ✓
- Per-file human sign-off composite → `+append` review PNG, Tasks 2–4 Step 2. ✓
- Correctness chain (Android-safe transforms + AE + on-device) → Global Constraints + Task 5 Step 5 note. ✓
- VectorPath expected to remain; byte-savings + lint measurement → Task 5 Steps 1–2. ✓
- Only the three files; scratch gitignored + removed; no version bump; commit/PR conventions; origin-only → Global Constraints + Tasks 2–5. ✓

**Placeholder scan:** No TBD/TODO; every code and command step is concrete. ✓

**Type consistency:** `optimize().out`/`optimizedData[]` (strings), `gate` JSON keys (`gatePass`, `aeMatrix`, `optimizedData`, `reviewPng`), and CLI modes (`selftest`/`check`/`gate`) are used consistently across Tasks 1–5. ✓

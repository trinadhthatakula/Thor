# Fonts

Self-hosted Latin subsets of the two families the APK ships. `../styles/fonts.css`
declares them; `OFL.txt` is the licence that redistributing them obliges us to carry.

Self-hosting is not a preference. A `<link>` to `fonts.googleapis.com` sends every
visitor's IP address to Google, on a site whose privacy stance is the argument it is
making — see the design spec, §5.3 and §7.

## Provenance

Sources are `app/src/main/res/font/`, unmodified, exactly as they go into the APK.
Nothing here is fetched from the network.

| Shipped file | Source | CSS `font-weight` | Bytes |
|---|---|---|---|
| `outfit-latin-400.woff2` | `outfit_regular.ttf` | 400 | 14595 |
| `outfit-latin-500.woff2` | `outfit_medium.ttf` | 500 | 14181 |
| `outfit-latin-600.woff2` | `outfit_semibold.ttf` | 600 | 14667 |
| `outfit-latin-700.woff2` | `outfit_bold.ttf` | 700 | 14565 |
| `outfit-latin-800.woff2` | `outfit_extrabold.ttf` | 800 | 14730 |
| `outfit-latin-900.woff2` | `outfit_black.ttf` | 900 | 14178 |
| `firacode-latin-var.woff2` | `firacode_variable.ttf` | `300 700` (variable) | 36078 |

`res/font/` also ships `outfit_thin.ttf` (100), `outfit_extralight.ttf` (200) and
`outfit_light.ttf` (300). They are declared in `Type.kt`'s `FontFamily` and referenced
by **no** typography role, so they are not shipped here. The six weights above are
exactly the set `AppTypography` asks for.

**No italic face ships, and none should.** `Type.kt` maps every `FontStyle.Italic`
entry back to the same file as its upright, so Thor has no real italic. `fonts.css`
declares no `font-style: italic` rule, and `check:markup` fails the build on `<em>`
and `<i>`.

## Re-running the conversion

The repo's fontTools lives in `.tools-venv` at the repo root. From the repo root:

```sh
# The nominal Latin subset: Google Fonts' `latin` range plus U+2190-2193, because the
# stock range carries only the up and down arrows and the copy uses -> and <-.
# Keep this string in sync with the `unicode-range` descriptors in ../styles/fonts.css.
U='U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+2074,U+20AC,U+2122,U+2190-2193,U+2212,U+2215,U+FEFF,U+FFFD'

for pair in \
  outfit_regular:outfit-latin-400   outfit_medium:outfit-latin-500 \
  outfit_semibold:outfit-latin-600  outfit_bold:outfit-latin-700 \
  outfit_extrabold:outfit-latin-800 outfit_black:outfit-latin-900 \
  firacode_variable:firacode-latin-var
do
  .tools-venv/bin/pyftsubset "app/src/main/res/font/${pair%%:*}.ttf" \
    --output-file="web/src/fonts/${pair##*:}.woff2" \
    --flavor=woff2 \
    --unicodes="$U"
done
```

Default `--layout-features` is deliberately not overridden: it already keeps `calt`,
which is the only feature Fira Code's ligatures use (its `GSUB` carries just `calt`
and `locl` — there is no `liga` table to preserve).

### If `--flavor=woff2` fails with a missing `brotli`

`pyftsubset` needs the `brotli` Python module to write WOFF2, and `.tools-venv` does
not currently have it. The fix is one command, and it is the right fix:

```sh
.tools-venv/bin/pip install brotli
```

The files currently in this directory were produced without it, offline: `pyftsubset`
wrote intermediate `.ttf` subsets, and a throwaway Node script packed each into WOFF2
using `zlib.brotliCompressSync` and the **null transform** for every table
(`transformVersion` 3 for `glyf`/`loca`, 0 for the rest), which the WOFF2 spec permits
and which needs no `glyf` transform implementation. Consequence: these files are a few
per cent larger than `woff2_compress` output. Re-running the command above once
`brotli` is installed will produce smaller, equally valid files.

## Verifying the output

`fontace` (already a transitive dependency of Astro, in `web/node_modules`) carries its
own independent WOFF2 and Brotli decoder, so it is a real check and not a round trip
through the same code that wrote the file:

```sh
cd web && node --input-type=module -e "
import { fontace } from 'fontace'
import fs from 'node:fs'
for (const f of fs.readdirSync('src/fonts').filter(n => n.endsWith('.woff2')).sort()) {
  const m = fontace(fs.readFileSync('src/fonts/' + f))
  console.log(f, m.format, m.weight, m.isVariable ? '(variable)' : '', m.style)
}
"
```

Every line must read `woff2`, the weight must match the table above, and every `style`
must read `normal`.

#!/usr/bin/env bash
# Pre-flight size gate for release notes.
#
# usage: check-notes-budget.sh [--require <file>]... <version-name> [wrapper-units]
#
# Telegram caps a sendDocument CAPTION at 1024 UTF-16 units and REJECTS an
# oversized one - it does not truncate, so an over-budget caption broadcasts
# NOTHING.
#
# release-rung.yml's curl carries --fail-with-body, so on the ladder that
# failure is at least visible: under GitHub's default `bash -e` shell the step
# goes red. It goes red mid-release, though. By then the Play upload has
# happened, and Create GitHub Release runs anyway - it is guarded by
# !cancelled() on purpose, so that a broadcast cannot veto a release. The
# outcome is a version live on Play and tagged on GitHub that nobody was told
# about, discovered from a red check.
#
# (telegram-release.yml carries the same --fail-with-body now. It used to send
# with a bare `curl -s ... > /dev/null`, which really did go green having posted
# nothing - the older, worse shape of the same failure. Note that redirecting
# stdout is all it takes to recreate it: writing the error body to stdout is the
# ONLY difference between --fail-with-body and --fail.)
#
# Either way, finding out at send time is finding out too late. That is why
# this runs pre-flight, before anything publishes: bolting it next to the curl
# would fail a half-published release.
#
# Measure UTF-16 units, not bytes and not characters. One emoji is a single
# character, two UTF-16 units, and four UTF-8 bytes.
#
# --require names a file the calling rung actually CONSUMES, which turns a
# missing one from a warning into a pre-flight error. release-rung.yml passes
# the list when require_notes is true. It is a list rather than a --strict flag
# because WHICH files a rung reads depends on the rung: one with telegram:
# false never reads telegram.md, and a gate on a file nothing reads can only
# ever fire falsely. Options must precede the positional arguments.
#
# wrapper-units is the size of the caption scaffolding the workflow wraps
# around telegram.md. It is NOT a constant - it contains the ref name and the
# actor - so release-rung.yml measures it per run and passes the result here
# rather than passing its declared floor. The caption that is really SENT costs,
# on the ladder's own three refs:
#   dev rung:        160 (own actor) / 163 (bot actor)
#   beta rung:       145 (own actor) / 148 (bot actor)
#   production rung: 146 (own actor) / 149 (bot actor)
# but a workflow_dispatch from a long branch name measures 187, because the dev
# figure above is for a three-character ref.
#
# What the workflow PASSES is not that number. At gate time fastlane has not
# written track.txt yet, so the measurement substitutes the longest label any
# rung can emit - dev's two-track 'Closed + Internal Testing' - on every rung.
# Pessimistic on purpose, and it means the values arriving here are 160/163 on
# dev (exact, since that label is dev's own), 158/161 on beta and 161/164 on
# production.
#
# Hence the 164 default, which exists only for invoking the script by hand: it
# is the largest wrapper the ladder can pass, so a hand run is never LOOSER than
# the release that follows it. It was 160 back when the longest label was
# 'Internal Testing' and 160 was exactly what all three rungs ended up using;
# adding the two-track label raised the gate on every rung and left that default
# 4 units light on production - a hand-run pass for a caption the release would
# refuse, i.e. the discovery this pre-flight exists to move earlier, moved back
# again. test-caption-wrapper.sh pins both the table above and this default
# against a fresh measurement, because this is the second copy of that table and
# the first one drifted the moment the label changed.
set -euo pipefail

required=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --require)
      if [ "$#" -lt 2 ]; then
        echo "::error::--require needs a filename" >&2
        exit 2
      fi
      required+=("$2")
      shift 2
      ;;
    --require=*)
      required+=("${1#--require=}")
      shift
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "::error::unknown option: $1" >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

version_name="${1:?usage: check-notes-budget.sh [--require <file>]... <version-name> [wrapper-units]}"
wrapper_units="${2:-164}"

TELEGRAM_CAP=1024
PLAYSTORE_CAP=500

# ${#required[@]} is safe on an empty array under set -u; "${required[@]}" is
# not, on bash 3.2. Hence the guard rather than a bare expansion.
is_required() {
  local needle="$1" f
  if [ "${#required[@]}" -eq 0 ]; then
    return 1
  fi
  for f in "${required[@]}"; do
    if [ "$f" = "$needle" ]; then
      return 0
    fi
  done
  return 1
}

dir="release-notes/v${version_name}"
[ -d "$dir" ] || dir="release-notes/${version_name}"
if [ ! -d "$dir" ]; then
  echo "::error::no release-notes directory for v${version_name}" >&2
  exit 1
fi

status=0

# Presence first, before any budget arithmetic. A rung that declares a file
# required has no fallback for it, so absence has to fail here - ahead of the
# Play upload, the GitHub release and the Telegram send - rather than degrade
# into a commit-log dump or a generic bullet that has already been broadcast.
if [ "${#required[@]}" -gt 0 ]; then
  for f in "${required[@]}"; do
    if [ ! -f "$dir/$f" ]; then
      echo "::error::no $f in $dir. This rung consumes it and requires curated notes, so there is nothing to fall back to." >&2
      status=1
    fi
  done
fi

telegram="$dir/telegram.md"
if [ -f "$telegram" ]; then
  units="$(python3 -c "
import sys
text = open(sys.argv[1], encoding='utf-8').read()
print(len(text.encode('utf-16-le')) // 2)
" "$telegram")"
  total=$((units + wrapper_units))
  if [ "$total" -gt "$TELEGRAM_CAP" ]; then
    echo "::error::telegram caption is ${total} UTF-16 units (${units} in file + ${wrapper_units} wrapper), cap is ${TELEGRAM_CAP}. Telegram rejects rather than truncates, and the send reports success either way." >&2
    status=1
  else
    echo "  telegram: ${total}/${TELEGRAM_CAP} units ($((TELEGRAM_CAP - total)) spare)"
  fi
elif ! is_required telegram.md; then
  # Required-and-absent was already reported above; do not say it twice.
  echo "::warning::no telegram.md in $dir" >&2
fi

playstore="$dir/playstore.txt"
if [ -f "$playstore" ]; then
  chars="$(python3 -c "
import sys
print(len(open(sys.argv[1], encoding='utf-8').read()))
" "$playstore")"
  if [ "$chars" -gt "$PLAYSTORE_CAP" ]; then
    echo "::error::playstore.txt is ${chars} characters, cap is ${PLAYSTORE_CAP}." >&2
    status=1
  else
    echo "  playstore: ${chars}/${PLAYSTORE_CAP} characters"
  fi
elif ! is_required playstore.txt; then
  # An error on every rung, required or not - the Play what's-new has no
  # fallback anywhere. The guard only avoids reporting it twice.
  echo "::error::no playstore.txt in $dir" >&2
  status=1
fi

exit "$status"

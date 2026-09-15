#!/usr/bin/env bash
# Select the lane without changing whether GitHub/Telegram publish. A release
# already promoted manually on Play still needs its signed APKs and stable tag.
# Usage: select-release-action.sh <publish> <rung> <code> <lane> <caption-status> [already-published-code]
set -euo pipefail

publish="${1:?publish is required}"
rung="${2:?rung is required}"
code="${3:?version code is required}"
lane="${4:?lane is required}"
caption_status="${5:?caption status is required}"
already_published_code="${6:-}"

fail() { printf '::error::%s\n' "$*" >&2; exit 1; }

case "$publish" in true|false) ;; *) fail "publish must be true or false" ;; esac
case "$rung" in
  dev) expected_lane=distribute_dev ;;
  beta) expected_lane=promote_beta ;;
  production) expected_lane=promote_production ;;
  *) fail "unknown release rung: $rung" ;;
esac
[ "$lane" = "$expected_lane" ] || fail "lane $lane does not match rung $rung"
[[ "$code" =~ ^[1-9][0-9]*$ ]] || fail "invalid release version code: $code"
case "$caption_status" in
  *$'\n'*|*$'\r'*) fail "caption status must be one line" ;;
esac

if [ -n "$already_published_code" ]; then
  [[ "$already_published_code" =~ ^[1-9][0-9]*$ ]] || fail "invalid already-published version code"
  [ "$rung" != dev ] || fail "the dev uploader cannot use a promotion exception"
fi

requires_play=false
play_already_published=false
if [ "$publish" != true ]; then
  lane=build_release_candidates
elif [ -n "$already_published_code" ] && [ "$code" = "$already_published_code" ]; then
  lane=build_release_candidates
  play_already_published=true
  caption_status='Already live on Play — GitHub release next'
else
  requires_play=true
fi

printf 'lane=%s\nrequires_play=%s\nplay_already_published=%s\ncaption_status=%s\n' \
  "$lane" "$requires_play" "$play_already_published" "$caption_status"

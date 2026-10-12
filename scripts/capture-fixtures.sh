#!/usr/bin/env bash
# Capture upstream payloads for tests.
#
#   scripts/capture-fixtures.sh manifest          # (re)capture every entry in fixtures.manifest
#                                                 #   -> src/test/resources/fixtures/<path>.json.gz
#   scripts/capture-fixtures.sh live              # one snapshot of today's slates for all leagues
#                                                 #   -> fixtures-raw/live/<UTC timestamp>/...
#   scripts/capture-fixtures.sh watch 300 240     # live snapshot every 300s for 240 minutes
#
# Live snapshots are deliberately NOT written into src/test: pick the interesting ones
# (halftime, intermission, OT, rain delay, ...) and copy them over by hand with a scenario name.
#
# Politeness: requests are sequential with a short pause and an identifying User-Agent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UA="sports-mcp-fixture-capture/0.1 (+https://github.com/matthewmgamble/sports-mcp)"
PAUSE=0.3

fetch() { # fetch <url> <out.json.gz>
    local url="$1" out="$2" tmp
    mkdir -p "$(dirname "$out")"
    tmp="$(mktemp)"
    # --compressed: ESPN gzips even when not asked; store decoded JSON, then gzip it ourselves
    # so every fixture has the same on-disk shape regardless of upstream encoding.
    if ! curl -fsSL --compressed -m 30 -A "$UA" -H "Accept: application/json" "$url" -o "$tmp"; then
        echo "FAIL $url" >&2
        rm -f "$tmp"
        return 1
    fi
    if ! jq -e . "$tmp" >/dev/null 2>&1; then
        echo "NOT JSON $url" >&2
        rm -f "$tmp"
        return 1
    fi
    jq -c . "$tmp" | gzip -9n > "$out"
    rm -f "$tmp"
    printf '%-60s %8s\n' "${out#"$ROOT"/}" "$(wc -c < "$out" | tr -d ' ')"
    sleep "$PAUSE"
}

capture_manifest() {
    local failed=0
    while read -r path url; do
        [[ -z "${path:-}" || "$path" == \#* ]] && continue
        fetch "$url" "$ROOT/src/test/resources/fixtures/$path.json.gz" || failed=$((failed + 1))
    done < "$ROOT/scripts/fixtures.manifest"
    [[ $failed -eq 0 ]] || { echo "$failed fixture(s) failed" >&2; exit 1; }
}

# League slates in ESPN's bucket (US Eastern) date.
capture_live() {
    local ts day dash dir
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    day="$(TZ=America/New_York date +%Y%m%d)"
    dash="$(TZ=America/New_York date +%Y-%m-%d)"
    dir="$ROOT/fixtures-raw/live/$ts"
    local espn=(
        "nfl football/nfl" "nba basketball/nba" "ncaaf football/college-football"
        "ncaab basketball/mens-college-basketball" "epl soccer/eng.1"
        "laliga soccer/esp.1" "bundesliga soccer/ger.1"
    )
    for entry in "${espn[@]}"; do
        set -- $entry
        fetch "https://site.api.espn.com/apis/site/v2/sports/$2/scoreboard?dates=$day" \
            "$dir/espn/$1-scoreboard.json.gz" || true
    done
    fetch "https://api-web.nhle.com/v1/score/$dash" "$dir/nhl/score.json.gz" || true
    fetch "https://statsapi.mlb.com/api/v1/schedule?sportId=1&date=$dash&hydrate=linescore,team" \
        "$dir/mlb/schedule.json.gz" || true
    # Per-game linescore for live MLB games (the schedule's hydrated linescore lags).
    local pks
    pks="$(gzip -dc "$dir/mlb/schedule.json.gz" 2>/dev/null \
        | jq -r '.dates[].games[] | select(.status.abstractGameState=="Live") | .gamePk' || true)"
    for pk in $pks; do
        fetch "https://statsapi.mlb.com/api/v1/game/$pk/linescore" "$dir/mlb/linescore-$pk.json.gz" || true
    done
}

case "${1:-}" in
    manifest) capture_manifest ;;
    live) capture_live ;;
    watch)
        interval="${2:-300}"
        minutes="${3:-180}"
        end=$(($(date +%s) + minutes * 60))
        while [[ $(date +%s) -lt $end ]]; do
            capture_live
            sleep "$interval"
        done
        ;;
    *)
        sed -n '2,12p' "$0"
        exit 2
        ;;
esac

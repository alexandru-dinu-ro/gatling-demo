#!/usr/bin/env bash
# Turns a performance workflow form into Maven -D arguments, one per line on stdout.
#
# Input (environment):
#   SETTINGS_JSON  the calling workflow's inputs, as produced by ${{ toJSON(inputs) }}
#
# Rules:
#   - every non-empty named input becomes -D<name>=<value> (empty = keep the default)
#   - mixWeights "a,b,c,d" becomes the four mix*Pct settings
#   - allowConcurrentRuns "default" is skipped; "true"/"false" are passed
#   - extraProperties is split on spaces; every item must be -Dname=value
#   - simulation is handled by the workflow itself and skipped here
#   - properties the workflow sets itself cannot be overridden
set -euo pipefail

SKIPPED_INPUTS='^(simulation|extraProperties|mixWeights|allowConcurrentRuns)$'
INPUT_NAME='^[A-Za-z][A-Za-z0-9]*$'
EXTRA_ITEM='^-D[A-Za-z][A-Za-z0-9_.-]*=.*$'
RESERVED='^-D(gatling\.simulationClass|perfSimulation|runEnvironment)='
MIX_WEIGHTS='^[0-9]+,[0-9]+,[0-9]+,[0-9]+$'
MIX_SETTINGS=(mixListAllPct mixListFilteredPct mixGetPolicyPct mixWritePct)

fail() {
  echo "::error::$*" >&2
  exit 1
}

settings="${SETTINGS_JSON:-}"
[[ -n "$settings" && "$settings" != "null" ]] || settings='{}'
jq -e 'type == "object"' >/dev/null <<<"$settings" || fail "SETTINGS_JSON is not a JSON object"

# Reads one input as a trimmed string ("" if absent or null).
input() {
  jq -r --arg key "$1" '(.[$key] // "") | tostring | gsub("^\\s+|\\s+$"; "")' <<<"$settings"
}

# 1. Named inputs
while IFS= read -r key; do
  [[ "$key" =~ $SKIPPED_INPUTS ]] && continue
  [[ "$key" =~ $INPUT_NAME ]] || fail "unexpected input name: $key"
  value="$(input "$key")"
  [[ -z "$value" ]] && continue
  [[ "$value" != *$'\n'* ]] || fail "input $key must be a single line"
  printf -- '-D%s=%s\n' "$key" "$value"
done < <(jq -r 'keys[]' <<<"$settings")

# 2. allowConcurrentRuns: default / true / false
concurrent="$(input allowConcurrentRuns)"
case "$concurrent" in
  "" | default) ;;
  true | false) printf -- '-DallowConcurrentRuns=%s\n' "$concurrent" ;;
  *) fail "allowConcurrentRuns must be default, true or false (was: $concurrent)" ;;
esac

# 3. mixWeights: four comma-separated whole numbers
weights="$(input mixWeights)"
if [[ -n "$weights" ]]; then
  [[ "$weights" =~ $MIX_WEIGHTS ]] || fail "mixWeights must be four whole numbers like 25,20,40,15 (was: $weights)"
  IFS=',' read -r -a parts <<<"$weights"
  for i in "${!MIX_SETTINGS[@]}"; do
    printf -- '-D%s=%s\n' "${MIX_SETTINGS[$i]}" "${parts[$i]}"
  done
fi

# 4. extraProperties: only -Dname=value items
extra="$(input extraProperties)"
if [[ -n "$extra" ]]; then
  read -r -a items <<<"$extra"
  for item in "${items[@]}"; do
    [[ "$item" =~ $EXTRA_ITEM ]] || fail "extraProperties accepts only -Dname=value items (rejected: $item)"
    [[ ! "$item" =~ $RESERVED ]] || fail "extraProperties cannot override ${item%%=*}; the workflow sets it"
    printf -- '%s\n' "$item"
  done
fi

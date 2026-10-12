#!/usr/bin/env bash
# Turns the "simulations" list of the All workflow into a run plan.
#
# Input (environment):
#   SIMULATIONS  comma-separated names in run order; short (LoadMixed) or full (LoadMixedSimulation),
#                any letter case; repeats allowed; empty entries ignored
# Output (appended to $GITHUB_OUTPUT, or printed when that is not set):
#   gate=true|false   true when the first entry is Smoke (it then runs alone first, as the gate)
#   rest=<JSON>       the remaining entries: [{"position":"02","label":"Load","simulation":"LoadListSimulation"}, ...]
#   rest_count=<n>    number of remaining entries
set -euo pipefail

KNOWN=(Smoke LoadList LoadCrud LoadMixed StressList StressGet StressCreate StressUpdate StressDelete StressMixed SpikeMixed SoakMixed)
GATE=Smoke
SUFFIX=Simulation
MAX_ENTRIES=99

fail() {
  echo "::error::$*" >&2
  exit 1
}

# Joins its arguments with ", ".
join() {
  local first="$1"
  shift
  printf '%s' "$first" "${@/#/, }"
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  printf '%s' "${value%"${value##*[![:space:]]}"}"
}

# Prints the canonical short name for an entry, or fails.
canonical() {
  local wanted="${1,,}"
  wanted="${wanted%"${SUFFIX,,}"}"
  local known
  for known in "${KNOWN[@]}"; do
    if [[ "${known,,}" == "$wanted" ]]; then
      printf '%s' "$known"
      return 0
    fi
  done
  return 1
}

label_of() {
  case "$1" in
    Smoke) echo Smoke ;;
    Load*) echo Load ;;
    Stress*) echo Stress ;;
    Spike*) echo Spike ;;
    Soak*) echo Soak ;;
  esac
}

IFS=',' read -r -a entries <<<"${SIMULATIONS:-}"
names=()
unknown=()
for entry in "${entries[@]}"; do
  entry="$(trim "$entry")"
  [[ -n "$entry" ]] || continue
  if name="$(canonical "$entry")"; then
    names+=("$name")
  else
    unknown+=("$entry")
  fi
done

if (( ${#unknown[@]} > 0 )); then
  fail "unknown simulation(s): $(join "${unknown[@]}"). Valid names: $(join "${KNOWN[@]}")"
fi
(( ${#names[@]} > 0 )) || fail "the simulations list is empty"
(( ${#names[@]} <= MAX_ENTRIES )) || fail "at most $MAX_ENTRIES entries are allowed (got ${#names[@]})"

gate=false
first_rest=0
if [[ "${names[0]}" == "$GATE" ]]; then
  gate=true
  first_rest=1
fi

rest='[]'
plan=()
for i in "${!names[@]}"; do
  position="$(printf '%02d' $((i + 1)))"
  name="${names[$i]}"
  if (( i < first_rest )); then
    plan+=("$position $name (gate)")
    continue
  fi
  plan+=("$position $name")
  rest="$(jq -c --arg position "$position" --arg label "$(label_of "$name")" --arg simulation "$name$SUFFIX" \
    '. + [{position: $position, label: $label, simulation: $simulation}]' <<<"$rest")"
done

echo "Plan:" >&2
printf '  %s\n' "${plan[@]}" >&2

target="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "gate=$gate"
  echo "rest=$rest"
  echo "rest_count=$(( ${#names[@]} - first_rest ))"
} >>"$target"

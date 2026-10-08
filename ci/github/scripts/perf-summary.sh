#!/usr/bin/env bash
# Writes the run summary (Markdown) for the GitHub Actions run page.
#
# Input (environment):
#   LABEL          test type, e.g. Stress
#   SIMULATION     simulation class name, e.g. StressUpdateSimulation
#   OUTPUT_FILE    saved console output of the simulation run (may be missing)
#   ARGS_FILE      arguments produced by perf-args.sh (may be missing)
#   GATE_OUTCOME   outcome of the unit-test gate step (success/failure/...)
#   RUN_OUTCOME    outcome of the simulation step (success/failure/skipped/...)
#   ARTIFACT_NAME  name of the uploaded artifact
# Output: appended to $GITHUB_STEP_SUMMARY, or printed when that is not set (local testing).
set -uo pipefail

summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
output="${OUTPUT_FILE:-}"
max_failed_lines=10

# Removes the log prefix "date time [LEVEL] [thread] [Class.method:line] - ".
strip_prefix() {
  sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]+ \[[A-Z ]+\] \[[^]]*\] \[[^]]*\] - //'
}

# Prints matching lines of the output, prefix stripped; nothing if the output is missing.
pick() {
  [[ -f "$output" ]] || return 0
  grep -E "$1" "$output" | strip_prefix || true
}

# Prints Gatling's final statistics only: the "> " lines after the last "Global Information"
# heading (the periodic progress reports during the run also start with "> ").
final_stats() {
  [[ -f "$output" ]] || return 0
  awk '/---- Global Information/ { stats = ""; capture = 1; next }
       capture && /^> / { stats = stats $0 "\n" }
       END { printf "%s", stats }' "$output"
}

# Prints a fenced block with a heading, only if there is content.
block() {
  local heading="$1" content="$2"
  [[ -n "$content" ]] || return 0
  printf '\n### %s\n\n```\n%s\n```\n' "$heading" "$content"
}

case "${GATE_OUTCOME:-}/${RUN_OUTCOME:-}" in
  success/success) verdict="✅ Passed" ;;
  success/*)       verdict="❌ Failed" ;;
  failure/*)       verdict="⛔ Not run: the unit-test gate failed" ;;
  *)               verdict="⛔ Not run: setup failed before the tests (secrets or inputs; see the job log)" ;;
esac

{
  printf '## %s · %s: %s\n' "${LABEL:-Performance}" "${SIMULATION:-?}" "$verdict"

  printf '\n### Settings overridden for this run\n\n'
  if [[ -s "${ARGS_FILE:-}" ]]; then
    printf '```\n%s\n```\n' "$(cat "$ARGS_FILE")"
  else
    printf 'None: committed defaults only.\n'
  fi

  block "Outcome" "$(pick 'planned length|finished:|Safety stop|Setup failed|configuration has|still the placeholder|: missing|must be')"
  block "Response times (all measured requests, ms)" "$(final_stats)"
  block "Assertions" "$(pick '^Global: ')"
  block "Time windows" "$(pick 'p95 |Recovery check')"

  failed_count=0
  [[ -f "$output" ]] && failed_count="$(grep -c 'Request failed:' "$output" || true)"
  if (( failed_count > 0 )); then
    block "Failed requests: ${failed_count} (first ${max_failed_lines} shown)" \
      "$(pick 'Request failed:' | head -n "$max_failed_lines")"
  fi

  printf '\n### Full results\n\nGatling report and run log: artifact **%s** (bottom of this page).\n' \
    "${ARTIFACT_NAME:-gatling-report}"
} >>"$summary"

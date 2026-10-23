#!/usr/bin/env bash
set -euo pipefail
agentic_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
agentic_jar_path="$agentic_script_dir/../agent-retry-demo.jar"
if [[ ! -f "$agentic_jar_path" ]]; then agentic_jar_path="$agentic_script_dir/../target/agent-retry-demo.jar"; fi
: "${GEMINI_MODEL:?Set GEMINI_MODEL to your tested model ID}"
if [[ -z "${GEMINI_API_KEY:-}" && -z "${GOOGLE_API_KEY:-}" ]]; then
  echo 'Set GEMINI_API_KEY locally; no scripted fallback.' >&2
  exit 2
fi
java -jar "$agentic_jar_path" agentic preflight --mode gemini --check-model
java -jar "$agentic_jar_path" agentic --mode gemini --fault "${1:-none}" --wait-ms 8000

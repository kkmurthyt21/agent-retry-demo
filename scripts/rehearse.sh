#!/usr/bin/env sh
set -eu
jar=${1:-agent-retry-demo.jar}
java -jar "$jar" demo reset
java -jar "$jar" demo preflight
java -jar "$jar" demo normal
java -jar "$jar" demo boundaries
set +e
java -jar "$jar" demo crash
crash_status=$?
set -e
if [ "$crash_status" -ne 23 ]; then
  printf 'Unexpected crash command exit: %s\n' "$crash_status" >&2
  exit 1
fi
java -jar "$jar" demo recover

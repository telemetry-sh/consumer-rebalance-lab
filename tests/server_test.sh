#!/bin/sh
set -eu

java_command=${1:-java}
app_jar=${2:-build/consumer-rebalance-lab.jar}
test_directory=$(mktemp -d)
server_log="$test_directory/server.log"
server_pid=

cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$test_directory"
}
trap cleanup EXIT INT TERM

PORT=0 "$java_command" -jar "$app_jar" >"$server_log" 2>&1 &
server_pid=$!

base_url=
attempt=0
while [ "$attempt" -lt 80 ]; do
  base_url=$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' "$server_log" | tail -n 1)
  if [ -n "$base_url" ] && curl --fail --silent "$base_url/healthz" >/dev/null; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 0.1
done

if [ -z "$base_url" ]; then
  echo "server did not report a listening URL" >&2
  cat "$server_log" >&2
  exit 1
fi

test "$(curl --fail --silent "$base_url/healthz")" = "ok"
curl --fail --silent "$base_url/" | grep -q "NOTHING WAS CONSUMING"
curl --fail --silent "$base_url/styles.css" | grep -q -- "--alert:"
curl --fail --silent "$base_url/app.js" | grep -q "renderStrategies"

api_response=$(curl --fail --silent \
  "$base_url/api/simulate?consumers=999&run_seconds=30&deploy_second=5")
printf '%s' "$api_response" | grep -q '"consumers":64'
printf '%s' "$api_response" | grep -q '"runSeconds":30'
printf '%s' "$api_response" | grep -q '"deploySecond":5'
printf '%s' "$api_response" | grep -q '"policy":"classic_eager"'
printf '%s' "$api_response" | grep -q '"policy":"incremental_warm_handoff"'

status=$(curl --silent --output "$test_directory/post.json" --write-out '%{http_code}' \
  --request POST "$base_url/api/simulate")
test "$status" = "405"
grep -q '"error":"method not allowed"' "$test_directory/post.json"

status=$(curl --silent --output "$test_directory/missing.json" --write-out '%{http_code}' \
  "$base_url/missing")
test "$status" = "404"
grep -q '"error":"not found"' "$test_directory/missing.json"

if HOST=not-an-interface "$java_command" -jar "$app_jar" >"$test_directory/host.log" 2>&1; then
  echo "invalid HOST unexpectedly succeeded" >&2
  exit 1
fi
grep -q "HOST must be" "$test_directory/host.log"

echo "ServerTest: HTTP, API normalization, errors, and host validation passed"

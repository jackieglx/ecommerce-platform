#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-}"
TIMEOUT="${TIMEOUT:-60}"

if [[ -z "$HOST" ]]; then
  echo "usage: $0 host:port" >&2
  exit 1
fi

start=$(date +%s)
while true; do
  if nc -z ${HOST%:*} ${HOST#*:} 2>/dev/null; then
    echo "$HOST is ready"
    exit 0
  fi
  now=$(date +%s)
  if (( now - start > TIMEOUT )); then
    echo "timeout waiting for $HOST" >&2
    exit 1
  fi
  sleep 1
done


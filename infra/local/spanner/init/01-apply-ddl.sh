#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DDL_DIR="$ROOT/ddl"

PROJECT="${SPANNER_PROJECT:-local-project}"
INSTANCE="${SPANNER_INSTANCE:-local-instance}"
DATABASE="${SPANNER_DATABASE:-local-db}"

# gcloud 用 REST 端口（9020）
REST_ENDPOINT="${SPANNER_EMULATOR_REST_ENDPOINT:-http://spanner:9020/}"

gcloud config set auth/disable_credentials true --quiet >/dev/null
gcloud config set project "$PROJECT" --quiet >/dev/null
gcloud config set api_endpoint_overrides/spanner "$REST_ENDPOINT" --quiet >/dev/null

shopt -s nullglob
DDL_FILES=($(find "$DDL_DIR" -type f -name "*.sql" | sort))
shopt -u nullglob

if [[ ${#DDL_FILES[@]} -eq 0 ]]; then
  echo "No DDL files found under $DDL_DIR"
  exit 0
fi

for file in "${DDL_FILES[@]}"; do
  echo "Applying DDL: $file"
  gcloud spanner databases ddl update "$DATABASE" \
    --instance="$INSTANCE" \
    --ddl-file="$file" \
    --quiet
done

echo "DDL applied to database=$DATABASE"

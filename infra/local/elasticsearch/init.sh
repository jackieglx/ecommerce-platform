#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${ENV_FILE:-$ROOT/.env}"
if [[ ! -f "$ENV_FILE" && -f "$ROOT/env.example" ]]; then
  ENV_FILE="$ROOT/env.example"
fi

source "$ENV_FILE"
ES_URL="${ELASTICSEARCH_URL:-http://localhost:9200}"

echo "Applying products_v1 index template to $ES_URL"
curl -sSf -XPUT "${ES_URL}/_index_template/products_v1" \
  -H "Content-Type: application/json" \
  --data-binary @elasticsearch/index-templates/products_v1.json

echo "Done."


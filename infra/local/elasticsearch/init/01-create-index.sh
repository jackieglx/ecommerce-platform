#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ES_URL="${ELASTICSEARCH_URL:-http://localhost:9200}"

echo "Applying products_v1 index template to $ES_URL"
curl -sSf -XPUT "${ES_URL}/_index_template/products_v1" \
  -H "Content-Type: application/json" \
  --data-binary @"${ROOT}/index-templates/products_v1.json"

echo "Ensuring products_v1 index exists"
if ! curl -sf "${ES_URL}/products_v1" > /dev/null; then
  curl -sSf -XPUT "${ES_URL}/products_v1" \
    -H "Content-Type: application/json" \
    --data-binary @"${ROOT}/mappings/products_v1.json"
fi

echo "Done."


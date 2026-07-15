#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/scripts/demo-products.tsv"
CATALOG_BASE_URL="${CATALOG_BASE_URL:-http://localhost:8080}"
INVENTORY_BASE_URL="${INVENTORY_BASE_URL:-http://localhost:8082}"

curl --fail --silent "$CATALOG_BASE_URL/actuator/health" >/dev/null
curl --fail --silent "$INVENTORY_BASE_URL/actuator/health" >/dev/null

count=0
while IFS=$'\t' read -r sku_id product_id title status brand price_cents currency inventory; do
  [[ "$sku_id" == "skuId" || -z "$sku_id" ]] && continue
  inventory="${inventory%$'\r'}"

  catalog_update="{\"title\":\"$title\",\"status\":\"$status\",\"brand\":\"$brand\",\"priceCents\":$price_cents,\"currency\":\"$currency\"}"
  http_status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$CATALOG_BASE_URL/api/v1/skus/$sku_id")"
  if [[ "$http_status" == "200" ]]; then
    curl --fail-with-body --silent --show-error -X PATCH "$CATALOG_BASE_URL/internal/admin/v1/skus/$sku_id" \
      -H 'Content-Type: application/json' -d "$catalog_update" >/dev/null
    echo "Updated Catalog SKU $sku_id"
  elif [[ "$http_status" == "404" ]]; then
    catalog_create="{\"skuId\":\"$sku_id\",\"productId\":\"$product_id\",\"title\":\"$title\",\"status\":\"$status\",\"brand\":\"$brand\",\"priceCents\":$price_cents,\"currency\":\"$currency\"}"
    curl --fail-with-body --silent --show-error -X POST "$CATALOG_BASE_URL/internal/admin/v1/skus" \
      -H 'Content-Type: application/json' -d "$catalog_create" >/dev/null
    echo "Created Catalog SKU $sku_id"
  else
    echo "Catalog lookup failed for $sku_id with HTTP $http_status" >&2
    exit 1
  fi

  curl --fail-with-body --silent --show-error -X POST "$INVENTORY_BASE_URL/internal/inventory/seed" \
    -H 'Content-Type: application/json' -d "{\"skuId\":\"$sku_id\",\"onHand\":$inventory}" >/dev/null
  echo "Seeded Inventory SKU $sku_id onHand=$inventory"
  count=$((count + 1))
done < "$MANIFEST"

echo "Demo data ready: $count Catalog SKUs with Inventory stock."

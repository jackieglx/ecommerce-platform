#!/usr/bin/env bash
set -euo pipefail

# bootstrap.sh 位置：infra/local/spanner/bootstrap.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INIT_DIR="$ROOT/init"

PROJECT="${SPANNER_PROJECT:-local-project}"
INSTANCE="${SPANNER_INSTANCE:-local-instance}"
DATABASE="${SPANNER_DATABASE:-local-db}"

# 你 compose 里 spanner emulator 的 REST endpoint（给 gcloud 用）
REST_ENDPOINT="${SPANNER_EMULATOR_REST_ENDPOINT:-http://spanner:9020}"

# 让 gcloud 配置只在这次运行生效，避免污染容器/默认配置
export CLOUDSDK_CONFIG="$(mktemp -d)"
trap 'rm -rf "$CLOUDSDK_CONFIG"' EXIT

# 统一在这里把 gcloud 指向 emulator（00/01 里即使再 set 也只会改这份临时配置）
gcloud config set auth/disable_credentials true --quiet >/dev/null
gcloud config set project "$PROJECT" --quiet >/dev/null
gcloud config set api_endpoint_overrides/spanner "$REST_ENDPOINT" --quiet >/dev/null

echo "[bootstrap] Ensuring instance/database exist..."
bash "$INIT_DIR/00-create-instance-db.sh"

# 用 “哨兵表” 判断是否已经 apply 过 DDL
# 你给的第一个表是 Skus，所以用它最合适
echo "[bootstrap] Checking whether DDL is already applied (sentinel table: Skus)..."
DDL_TEXT="$(gcloud spanner databases ddl describe "$DATABASE" --instance="$INSTANCE" --quiet 2>/dev/null || true)"

if echo "$DDL_TEXT" | grep -qE 'CREATE TABLE[[:space:]]+Skus[[:space:]]*\('; then
  echo "[bootstrap] Found table Skus. Skip applying DDL."
else
  echo "[bootstrap] Table Skus not found. Applying DDL files..."
  bash "$INIT_DIR/01-apply-ddl.sh"
fi

echo "[bootstrap] Done. project=$PROJECT instance=$INSTANCE database=$DATABASE rest=$REST_ENDPOINT"

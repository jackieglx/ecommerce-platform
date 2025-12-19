PROJECT="${SPANNER_PROJECT:-local-project}"
INSTANCE="${SPANNER_INSTANCE:-local-instance}"
DATABASE="${SPANNER_DATABASE:-local-db}"

REST_ENDPOINT="${SPANNER_EMULATOR_REST_ENDPOINT:-http://spanner:9020/}"
GRPC_HOST="${SPANNER_EMULATOR_HOST:-spanner:9010}"

gcloud config set auth/disable_credentials true --quiet >/dev/null
gcloud config set project "$PROJECT" --quiet >/dev/null
gcloud config set api_endpoint_overrides/spanner "$REST_ENDPOINT" --quiet >/dev/null

echo "Creating Spanner emulator instance/database if not present..."
gcloud spanner instances create "$INSTANCE" \
  --config=emulator-config \
  --description="Local emulator" \
  --nodes=1 --quiet 2>/dev/null || true

gcloud spanner databases create "$DATABASE" \
  --instance="$INSTANCE" --quiet 2>/dev/null || true

echo "Spanner emulator ready: project=$PROJECT instance=$INSTANCE database=$DATABASE rest=$REST_ENDPOINT grpc=$GRPC_HOST"

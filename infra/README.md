## Local stack (infra/local)

Goals: one-command up/down/reset, reproducible dependencies for local dev.

What’s included:
- Spanner Emulator (`spanner`, with `spanner-tools` for gcloud)
- Kafka + Zookeeper (bitnami)
- Redis
- Elasticsearch single-node (auth disabled)

Quick start:
1) `cp infra/local/env.example infra/local/.env` (or export ENV_FILE to point to your own)
2) `infra/local/scripts/up.sh`
3) Wait for services (optional) `infra/local/scripts/wait-for.sh localhost:9200`
4) Init Spanner + DDL:
   - `docker compose --env-file infra/local/.env -f infra/local/docker-compose.yml run --rm spanner-tools bash spanner/init/00-create-instance-db.sh`
   - `docker compose --env-file infra/local/.env -f infra/local/docker-compose.yml run --rm spanner-tools bash spanner/init/01-apply-ddl.sh`
5) Kafka topics: `infra/local/kafka/topics.sh`
6) ES template: `infra/local/elasticsearch/init.sh`

Utilities:
- `infra/local/scripts/down.sh` – stop stack
- `infra/local/scripts/reset.sh` – stop + remove volumes (clean slate)
- `infra/local/scripts/wait-for.sh host:port` – simple port waiter

## K8s / GCP / CI (skeleton)

- `infra/k8s/`: reserve for kustomize base + overlays (dev/prod).
- `infra/gcp/`: docs for WI/IAM/spanner access/runbooks.
- `infra/ci/`: wire docker-compose into integration tests when ready.


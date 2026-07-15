## Local stack (infra/local)

Goals: one-command up/down/reset, reproducible dependencies for local dev.

What’s included:
- Spanner Emulator (`spanner`, with `spanner-tools` for gcloud)
- Kafka 7.6.1 single-node KRaft (no ZooKeeper dependency)
- Redis
- Elasticsearch single-node (auth disabled)

The local compose stack does not include a Spanner Change Streams connector or a cache-invalidation service. Order and Payment publish their Outbox records from scheduled application processes; Catalog broadcasts cache invalidations through Redis Pub/Sub.

Quick start (Docker dependencies + Java services on the host):
1) Linux/macOS: `./scripts/run-local.sh`
2) Windows PowerShell: `./scripts/run-local.ps1`
3) Run the smoke test: `./scripts/smoke-local.sh` or `./scripts/smoke-local.ps1`

`infra/local/.env` is Compose-only: its `spanner:9010` and `kafka:9092` addresses work only from containers. `infra/local/host.env.example` documents the host-side values, including `SPANNER_EMULATOR_HOST=localhost:9010` and `KAFKA_BOOTSTRAP_SERVERS=localhost:29092`. The launch scripts set those host variables explicitly.

Manual dependency initialization (the launch scripts do this automatically):
1) `infra/local/scripts/up.sh`
2) `docker compose --env-file infra/local/.env -f infra/local/docker-compose.yml run --rm --entrypoint bash spanner-tools spanner/bootstrap.sh`
3) Kafka topics: `infra/local/kafka/topics.sh`
   - The script provisions every application event topic defined in `libs/contracts/src/main/java/com/lingxiao/contracts/Topics.java`.
   - It also provisions the generic consumer DLTs (`<source-topic>.DLT`) used by `common-kafka`; these retain the source topic's partition count.
   - Main event topics use 6 partitions, explicit flash-sale outbox DLQs use 3, and every local topic uses replication factor 1 for the single Kafka broker.
   - Kafka uses a fixed local KRaft cluster ID and the `kafka_kraft_data` volume, avoiding cluster-ID drift between independently persisted Kafka and ZooKeeper volumes.
6) ES template: `infra/local/elasticsearch/init.sh`

Utilities:
- `infra/local/scripts/down.sh` – stop stack
- `infra/local/scripts/reset.sh` – stop + remove volumes (clean slate)
- `infra/local/scripts/wait-for.sh host:port` – simple port waiter

## K8s / GCP / CI

These directories are planned only and are not present in this repository. There is currently no Kubernetes manifest set, GCP deployment/runbook, or CI pipeline implementation.


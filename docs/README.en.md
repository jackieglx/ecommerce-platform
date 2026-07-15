# High-Throughput Distributed E-commerce Flash Sale Platform (Microservices)

- 📄 English: this page
- 📄 中文: [README.zh-CN](../README.zh-CN.md)
- 🏗️ High Level Design: [high-level-design.md](high-level-design.md)

## One-liner

A **high-concurrency, distributed flash-sale (seckill) backend** built with microservices. It delivers **fast user response without overselling**, ensures **reliable timeout cancellation & stock rollback**, and uses an **event-driven, eventually consistent** architecture powered by **Redis Lua**, **Redis Stream → Kafka**, **database-Outbox polling publishers**, and **Spark Streaming + Elasticsearch** for 7-day best-seller ranking.

---

## Background & My Role

I was interested in the trade-offs and failure modes under extreme concurrency, so I proposed and led the **flash-sale platform** direction. Beyond early architecture discussions, I **independently implemented the core backend pipeline**, including:

- Flash-sale ordering critical path: Redis inventory, **Lua atomic checks/deduction**, Stream events, Stream→Kafka forwarding, Order Service persistence
- Order timeout cancellation: **Outbox polling** reliable publishing, Redis delay queue (dual ZSET + ownership), failure recovery
- High-QPS read path: L1/L2 caching (**Caffeine + Redis**), SingleFlight anti-stampede, distributed cache invalidation
- Search & “7-day best sellers”: ES indexing, **streaming sales_7d aggregation**, hourly batch updates for ranking/sorting

---

## Design Goals

- **Flash sale peak order traffic:** target **≥ 50k QPS** burst handling
- **Product browsing/query traffic:** target **millions of QPS** via caching
- **Consistency model:** prevent oversell on the critical path; cross-service updates via events with **eventual consistency**
- **Reliability:** tolerate at-least-once delivery using **idempotent consumers** and reliable event publishing patterns

---

## Key Challenges (Flash Sale) and How I Addressed Them

### 1) Ultra-fast response while preventing oversell
**Approach: shorten the synchronous path + early reject invalid requests + atomic inventory operations**

- Keep the critical path in **Redis** (avoid DB contention under spikes)
- Early reject: out-of-stock, duplicate purchase, invalid idempotency requests
- Use **Redis Lua** to make “check + deduct + record + emit event” **atomic**

### 2) Reliable timeout cancellation and stock rollback
**Approach: reliable timeout-event publishing + recoverable delay queue + conditional state transitions + stock release events**

- Create orders and timeout events using **Outbox (same DB transaction)**
- A scheduled publisher claims pending Outbox rows, sends them to Kafka, then marks them sent or retryable
- Execute timeout tasks via a **recoverable Redis delay queue** design
- Roll back stock through Kafka and idempotent inventory updates

### 3) High-QPS reads with correctness trade-offs
**Approach: L1/L2 caching + stampede protection + cache-aside invalidation**

- L1: **Caffeine** (local, fastest)
- L2: **Redis** (shared)
- Stampede protection: **SingleFlight** request coalescing
- Consistency: the Catalog write path deletes L2 then L1 and broadcasts an invalidation through Redis Pub/Sub

---

## Architecture Overview (High Level)

```
Client
  │
  ▼
Direct service endpoints (gateway-service is currently a placeholder)
  │
  ├─ Flash Sale API ───────────────► Inventory Service (Redis + Lua)
  │                                   │
  │                                   ├─ Redis Stream (flashsale events)
  │                                   ▼
  │                              Stream Forwarder
  │                                   │ (XACK only after Kafka ack)
  │                                   ▼
  │                               Kafka Topics
  │                                   │
  │                                   ▼
  │                              Order Service (Spanner)
  │                                   │
  │                                   ├─ Outbox (same txn)
  │                                   ▼
  │                       Scheduled Outbox publisher → Kafka
  │                                   │
  │                                   ▼
  │                         OrderTimeoutProcessor (Redis delay queue)
  │
  └─ Search API ───────────────► Search Service (Elasticsearch)
                                  ▲
                                  │
                           Spark Streaming (sales_7d)
                                  ▲
                                  │
                         Kafka (OrderPaid events)
```

---

## Tech Stack

- **Java 21**
- **Spring Boot 3.5.x / Spring Cloud 2025.x**
- **Google Cloud Spanner** (distributed relational DB)
- **Redis** (inventory, delay queue, idempotency, caching, Pub/Sub)
- **Kafka** (event bus)
- **Elasticsearch** (search + ranking fields)
- **Apache Spark Streaming** (7-day sales aggregation)

---

## Services & Modules

- **Gateway Service**: placeholder module printing `Hello, World!`; routing, authentication, and rate limiting are not implemented
- **Catalog Service**: product/SKU CRUD and product-change events
- **Search Service**: ES query/filter/sort + near-real-time indexing
- **Inventory Service**: inventory + flash sale; Redis Lua atomic pipeline
- **Order Service**: order lifecycle in Spanner; Outbox writes
- **Payment Service**: payment state + `OrderPaid` events
- **Cart/User/Notification Services**: foundational user/cart/notification capabilities
- **Jobs / sales-7d-streaming-job**: Spark Streaming to maintain `sales_7d`
- **libs/**: shared libraries (contracts, Kafka/Redis wrappers, idempotency, etc.)

---

## Local Development: Host Java + Compose Dependencies

The supported local mode runs only Spanner Emulator, Kafka, Redis, and Elasticsearch in Docker. Catalog, Search, Inventory, Order, and Payment run as Java processes on the host; this repository has no service Dockerfiles and Compose does not start Java services.

Prerequisites: Docker Desktop/Engine with Compose, Java 21, Maven, `curl` (or PowerShell), and Bash on Windows when using the `.ps1` launcher (Git for Windows supplies it for Kafka topic initialization). No cloud account or external secret is required.

Start everything:

```bash
./scripts/run-local.sh                 # Linux/macOS
pwsh -File .\scripts\run-local.ps1    # Windows PowerShell
```

Seed 20 fixed demo products and their real Inventory stock, then start the frontend:

```powershell
.\scripts\seed-demo-data.ps1
cd frontend
Copy-Item .env.example .env
npm install
npm run dev
```

On Linux/macOS use `./scripts/seed-demo-data.sh` and `cp frontend/.env.example frontend/.env`. The example env already contains every fixed demo SKU ID; the frontend resolves them through the real Catalog batch API without manual SKU entry.

The script starts dependencies, idempotently bootstraps Spanner DDL, initializes Kafka topics, builds the project, then starts the five core services with `SPRING_PROFILES_ACTIVE=local`. Use `--deps-only` / `-Mode deps-only` to start only dependencies, then `--services-only` / `-Mode services-only` in another terminal to start the Java services. Logs and PID files are under `.local/`.

Container and host addresses are deliberately different: Compose tooling uses `SPANNER_EMULATOR_HOST=spanner:9010` and Kafka `kafka:9092`; host Java services must use `SPANNER_EMULATOR_HOST=localhost:9010` and `KAFKA_BOOTSTRAP_SERVERS=localhost:29092`. `infra/local/host.env.example` records all host values. `local` is required because SKU write endpoints and Inventory seed/admin endpoints are profile-gated; without it they return 404.

Minimal smoke test:

```bash
./scripts/smoke-local.sh
pwsh -File .\scripts\smoke-local.ps1
```

It checks all five `/actuator/health` endpoints, creates a SKU, seeds inventory (which preheats its price), reserves one flash-sale item, waits for the Kafka-driven Order creation, submits payment, and polls the real `GET /api/v1/orders/{orderId}` endpoint until the Order is `PAID`.

Stop with `./scripts/stop-local.sh` or `pwsh -File .\scripts\stop-local.ps1`. For a destructive clean slate, run `infra/local/scripts/reset.sh` (it removes Docker volumes). `infra/k8s`, `infra/gcp`, and `infra/ci` are planned only and do not exist yet.

---

## Deep Dives

### 1) Flash Sale Ordering: Redis Lua + Stream events + Kafka async persistence

**Goal:** fast response + no oversell + no lost events

**How it works:**
- Preload inventory into **Redis**
- On request, a **Lua script** atomically performs:
  1. Stock check
  2. “one-user-one-order” guard (e.g., Redis Set)
  3. Idempotency check (Idempotency Key)
  4. Stock deduction
  5. Append a “reserve/order-intent” event into **Redis Stream**
- Once Lua returns success, the API immediately responds to the client (proceed to payment). DB writes and downstream steps are **fully asynchronous**.
- **Reliability (Stream → Kafka):**
  - A forwarder service reads Stream entries and publishes to Kafka.
  - It only `XACK`s the Stream entry **after Kafka confirms** the publish, ensuring Stream events are not dropped.
- **Idempotent consumers:** all consumers use a custom Spring AOP annotation-based idempotency mechanism, so duplicates do not create duplicate side effects.

---

### 2) Order Timeout Cancellation: Outbox Polling + Recoverable Delay Queue

**Goal:** timeouts are eventually processed; stock is eventually released; crashes are recoverable.

#### 2.1 Reliable timeout-event publishing (no “DB committed but message lost”)
When Order Service creates an order, it writes within the **same Spanner transaction**:
1. Insert the order row
2. Insert a **NEW** timeout-cancel event into an **Outbox** table

After commit, the in-service scheduled Outbox publisher claims pending rows, publishes them to Kafka, and marks each row sent or retryable. No local Spanner Change Streams connector is included. The `OrderTimeoutProcessor` consumes and schedules timeout handling.

#### 2.2 Delay queue design: dual ZSET + ownership
- `ready ZSET`: pending tasks (`member=orderId`, `score=expireAt`)
- `processing ZSET`: leased tasks being processed (`member=orderId`, `score=leaseTime`)
- `owner HASH`: task ownership (`field=orderId`, `value=token(uuid + threadId)`)

Claiming moves tasks **atomically** from `ready → processing` and assigns ownership to avoid duplicate execution.

#### 2.3 Execution and stock release
- Processor performs a **conditional update** in Spanner (e.g., only cancel if status is still `PENDING_PAYMENT`)
  - If already paid/cancelled: do nothing
  - If cancellation succeeds: write a “release stock” event to Outbox **within the same transaction**
- The same scheduled Outbox publisher sends release events to Kafka; Inventory Service consumes them to idempotently restore Redis inventory.

#### 2.4 Failure recovery
If a worker crashes after claiming a task:
- A recovery routine scans `processing ZSET` for expired leases and re-queues tasks back into `ready ZSET`, clearing ownership so other workers can retry.

---

### 3) High-QPS Reads: L1/L2 Cache + SingleFlight anti-stampede

**Use case:** product details are read-heavy and must scale under bursts.

#### 3.1 Read path: L1 → L2 → DB
- L1 miss + L2 hit: fill L1 from Redis
- L1 miss + L2 miss: query DB

To prevent DB stampedes on L2 misses, each instance uses **SingleFlight**:

- Maintain `ConcurrentHashMap<skuId, CompletableFuture<CacheValue>>` as the in-flight table
- The first thread that `putIfAbsent` wins becomes the leader (query DB + fill Redis + fill Caffeine + complete future)
- Other threads wait on the same future instead of hitting DB
- If a follower times out, it best-effort removes the entry to avoid long-lived blockage

> If needed, this can be extended with a distributed lock on L2 misses, but that adds latency/complexity.

#### 3.2 Write path: cache-aside invalidation + distributed L1 purge
Catalog's write service performs cache-aside invalidation after the database transaction commits:
1. Delete the Redis L2 entry, then evict the local Caffeine L1 entry
2. Publish the SKU invalidation via Redis Pub/Sub
3. Each Catalog instance subscribes and evicts its local Caffeine (L1)

There is no separate Cache Invalidation Service or Change Streams-based invalidation component.

To mitigate “stale value re-population,” options include:
- **Versioned cache writes** (recommended): only accept writes with newer versions
- **Delayed double delete** (fallback): delete immediately, then delete again after a delay

---

### 4) Search & 7-day Best Sellers: Kafka → Spark Streaming → Elasticsearch

**Goal:** support sorting by `sales_7d` and a “7-day best sellers” leaderboard.

#### Why 7 days
- More stable than daily ranking (trend-friendly)
- Lower storage/compute cost than monthly/overall ranking

#### Pipeline
- `OrderPaid` events are published to Kafka by the Payment Outbox polling publisher
- Spark Streaming runs processing-time micro-batches once per minute, groups by `skuId`, and maintains state via `flatMapGroupsWithState`
- A changed SKU is written once per micro-batch; idle SKU state is woken hourly so expiry changes are also sent to ES

#### Per-SKU state design
- `bucketVal[168]`: ring buffer of hourly increments (7 * 24)
- `slotHour[168]`: which hour each bucket currently represents
- `sum7d`: rolling sum
- `dirty`: whether this SKU changed since last emit
- `lastEmitHour / lastAdvancedHour`: diagnostics for the most recent output and window advance

Processing-time timeouts wake each active SKU hourly. On each wake-up the job scans its fixed 168 buckets, removes buckets outside the current seven-day window, and writes the changed value (including zero) to ES. Once the final bucket expires, the zero is persisted and the state is removed. This scans no database or Elasticsearch index; its CPU cost is 168 simple bucket checks per active SKU per hour.

---

## Repository Structure

```
ecommerce-platform/
├── services/          # microservices
│   ├── gateway-service/
│   ├── catalog-service/
│   ├── search-service/
│   ├── inventory-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── cart-service/
│   ├── user-service/
│   └── notification-service/
├── jobs/              # batch/streaming jobs
│   └── sales-7d-streaming-job/
├── libs/              # shared libraries (contracts, idempotency, kafka/redis wrappers)
└── infra/             # infrastructure configs (local/containerized)
```

---


## Docs

- High Level Design: `docs/high-level-design.md`

---

**Docker Desktop 重启/容器重建后**：跑

```bash
docker compose exec spanner-tools bash -lc "cd /workspace/infra/local/spanner && bash ./bootstrap.sh"

```





**只是改代码重启 catalog-service**：不需要跑 init 脚本（只要 emulator 没重启、DB 还在）



```bash
curl.exe -X POST "http://localhost:8080/internal/skus" -H "Content-Type: application/json" -d '{"skuId":"sku_1","productId":"prod_1","title":"iPhone 15","status":"ACTIVE","priceCents":129900,"currency":"USD"}'

curl.exe "http://localhost:8080/products/sku_1"
```



`products_v1` mapping

```json
PUT products_v1
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "analysis": {
      "normalizer": {
        "lowercase_normalizer": {
          "type": "custom",
          "filter": ["lowercase", "asciifolding"]
        }
      },
      "filter": {
        "autocomplete_filter": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20
        }
      },
      "analyzer": {
        "product_text": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "asciifolding"]
        },
        "autocomplete_index": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "asciifolding", "autocomplete_filter"]
        },
        "autocomplete_search": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "asciifolding"]
        }
      }
    }
  },
  "mappings": {
    "dynamic": "strict",
    "properties": {
      "skuId": { "type": "keyword" },
      "productId": { "type": "keyword" },

      "title": {
        "type": "text",
        "analyzer": "product_text",
        "search_analyzer": "product_text",
        "fields": {
          "keyword": {
            "type": "keyword",
            "ignore_above": 256,
            "normalizer": "lowercase_normalizer"
          },
          "autocomplete": {
            "type": "text",
            "analyzer": "autocomplete_index",
            "search_analyzer": "autocomplete_search"
          }
        }
      },

      "status": { "type": "keyword" },

      "priceCents": { "type": "long" },
      "currency": { "type": "keyword" },

      "sales7d": { "type": "long" },

      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" },

      "brand": {
        "type": "keyword",
        "normalizer": "lowercase_normalizer"
      }
    }
  }
}
```





```bash
# =========================
# 本地 Search 链路 Smoke Test（Catalog -> Index -> ES -> /search）
# 前置：CatalogService 和 SearchService 已经用 local profile 启动
#   - catalog-service: 8080
#   - search-service : 8081
# =========================

# 1) 启动本地依赖（Spanner Emulator / Kafka / Redis / Elasticsearch / spanner-tools）
#    目的：把本地运行所需的基础设施都拉起来（后台运行）
docker compose -f infra/local/docker-compose.yml up -d

# 2) 验证 Elasticsearch 是否可访问
#    预期：返回 cluster_name/version/tagline 等信息，说明 ES 在 9200 正常工作
Invoke-RestMethod http://localhost:9200

# 3) 初始化 Elasticsearch 的 index template + index（products_v1）
#    目的：创建/更新 products_v1 的 template + mapping，保证后续写入文档字段类型正确
cd E:\ecommerce-platform\infra\local\elasticsearch\init
powershell -ExecutionPolicy Bypass -File .\01-create-index.ps1

# 4) 初始化 Spanner：创建 instance/database（如不存在）并应用所有服务的 DDL
#    目的：让 catalog/order/inventory/payment 等服务需要的表结构在 emulator 里就绪
cd E:\ecommerce-platform
docker compose -f infra/local/docker-compose.yml exec spanner-tools bash -lc "cd /workspace/infra/local/spanner/init && ./00-create-instance-db.sh && ./01-apply-ddl.sh"

# 5) 写入一条 SKU 到 catalog（内部接口，仅 local profile 下存在）
#    目的：向 catalog 的数据库造数据，作为 search 索引的数据源
#    预期：返回 SkuResponse（包含 skuId/productId/title/brand/priceCents/createdAt 等）
$CATALOG="http://localhost:8080"
curl.exe -sS -X POST "http://localhost:8080/internal/skus" `
  -H "Content-Type: application/json" `
  --data-binary "@infra/local/sku.json"

# 6) 触发 search-service 对指定 skuIds 建索引（写入 Elasticsearch）
#    目的：让 search-service 拉取 catalog 数据并写入 ES（products_v1）
#    预期：200 + {"requested":x,"indexed":y,"missing":[...]}，indexed 应该 >= 1
curl.exe -i -X POST "http://localhost:8081/internal/search/indexSkus" `
  -H "Content-Type: application/json" `
  --data-binary "@infra/local/index-request.json"

# 7) 验证对外搜索接口：关键词检索（q）
#    目的：确认 search-service 能从 ES 查到数据并返回 SearchResult
#    预期：{"total":1,"items":[...]}，items 中包含刚写入的 sku
curl.exe -sS "http://localhost:8081/search?q=iphone"

# 8) 验证对外搜索接口：品牌过滤（brand）
#    目的：确认 brand filter 生效
#    预期：brand=Apple 时仍能返回 Apple 的 sku（有多条数据时可以验证排除效果）
curl.exe -sS "http://localhost:8081/search?q=iphone&brand=Apple"

# 9) 验证对外搜索接口：价格区间过滤（minPrice/maxPrice）
#    目的：确认 range filter 生效（注意单位通常是 priceCents）
#    预期：sku 的 priceCents 落在区间内则返回，否则 total=0
curl.exe -sS "http://localhost:8081/search?q=iphone&minPrice=100000&maxPrice=150000"

# 10) 验证对外搜索接口：分页参数（page/size）
#     目的：确认分页参数能被正确解析并应用
#     预期：当前只有 1 条数据时 page=1,size=20 仍返回 1 条
curl.exe -sS "http://localhost:8081/search?q=iphone&page=1&size=20"

```



# 进度

2025-12-22-milestone4

- 在本地环境中验证 Catalog 写入 SKU 后会通过 Kafka 事件触发 Search 自动索引到 Elasticsearch，未调用任何手动 reindex 接口，`GET /search` 在 1–2 秒内可检索到新 SKU。



2025-12-22-milestone5

- ✅ 同 eventId 重复投递 5 次，search-service 只处理 1 次（日志显示 skip）
- ✅ Redis 中存在 `idem:v1:search:index:event:<eventId>`，TTL 正常
- ✅ /search 能搜到对应商品（索引链路没断）



# Milestone

## Milestone 4：Catalog 写入后自动索引（事件驱动 Indexing）

### 目标

**用户在 catalog-service 创建/更新 SKU 后，search-service 自动更新 ES，不再依赖手动调用 `/internal/search/indexSkus`。**

### 为什么这是下一步最值

- 这是搜索系统的“必经之路”：ES 本质是**派生存储**，必须靠事件同步。
- 你已经有 Kafka/Redis/ES/Spanner，本 milestone 可以把 Kafka 真正用起来，但**不引入订单复杂度**。
- 验收标准非常明确，可自动化测试。

### 需要做什么（不 over-engineering）

**新增一个事件：`SkuUpserted`（或 `SkuCreated`）**

- 在 `catalog-service` 里：成功写入 Spanner 后，**发 Kafka 消息**
- 在 `search-service` 里：消费消息，调用 catalog batchGet（或直接带 payload），写入 ES

> payload 设计二选一（推荐先选 A）

- A（推荐）：事件只带 `skuId`（小而稳定），search-service 再去 catalog batchGet 拉数据
- B：事件带完整 sku 文档（减少一次 RPC，但 schema 演进压力更大）

### 验收（Definition of Done）

1. `POST /internal/skus` 创建 sku 后
2. **不调用任何 search internal 接口**
3. 等待很短时间（比如 1-2s）
4. `GET /search?q=iphone&brand=Apple` 能搜到新商品
5. 重复发同一个事件 / 重启 consumer 不会写乱（至少做到“可重复消费不致命”）

### 涉及模块改动（清晰且少）

- `libs/contracts`：新增 `SkuUpsertedEvent` DTO（以及 topic 名常量）
- `libs/common-kafka`：提供 producer/consumer 的基础配置（你现在如果已有就复用）
- `catalog-service`：在写成功后 publish event
- `search-service`：加一个 Kafka listener + 调用 IndexService

------





## Milestone 5：消费幂等 + 可恢复



**目标**

- search-service 消费 `SkuUpsertedEvent` 时实现幂等：同一个 eventId 在 TTL 内只处理一次
- 降低重复消费带来的重复 RPC/重复索引开销
- 失败可重试，必要时可进入 DLT（可选）

**实现（最小版本）**

- Redis 去重：`SET idem:search:index:event:{eventId} 1 NX EX 30m`
- 命中去重直接 return（记录 skipped metric）
- 正常路径：catalog batchGet → index ES
- 保持 ES 覆盖写（天然幂等）

**验收**

- 同一个 eventId 连续投递 10 次：search-service 只处理一次（可通过日志/metrics 证明）
- 同 skuId 不同 eventId 投递 10 次：10 次都能处理（不会误吞更新）
- consumer 重启后重复投递同一 eventId：TTL 内仍只处理一次
   -（可选加分）失败重试超过阈值进入 DLT，可人工回放



### 核心目标

**在 search-service 的 Kafka consumer 入口做幂等**，避免重复消费带来的重复 RPC/重复索引，并且可观测。

### 最小实现（推荐你这样写）

- **不需要 AOP**（AOP 是加分项，但不是最小）
- 直接在 consumer 里做 3 行逻辑：
  1. `SETNX idem:search:index:{eventId} = 1 EX 30m`
  2. 如果返回 false → 直接 ack/return（视你的 ack 模式）
  3. 成功则继续 batchGet + index

**为什么不强推 AOP**：
 你现在项目 milestone 要快交付，AOP 往往会引入：

- SpEL 解析 key 的复杂度
- 代理顺序/事务边界/异常传播的坑
   等你第一个 consumer 做稳了，再抽成 `common-idempotency` 更顺。

> 但如果你就是想把它做成亮点模块：可以在“最小实现跑通”后，再用 AOP 抽象出来（作为 Milestone 5.1）。

### key 设计

- `idem:search:index:event:{eventId}`（TTL 10–30 分钟，或者更长：24h 也行）
- **一定要用 eventId**，别用 skuId。









## milestone6

**order-service（真·状态机/事实源）**

- 存 Orders / OrderItems
- 控制状态流转（CREATED → RESERVED → PAID/CANCELED）
- 对外 API 就是你列的那 4 个

**inventory-service（强一致库存 + 预占/释放）**

- 存 Inventory
- 提供 reserve/release（可以是内部 API）

**payment-service（stub）**

- 只返回成功/失败（或提供 confirm 接口）
- **不直接写 Orders 表**（订单状态还是由 order-service 统一修改）



## 你需要改哪些 module / 哪些逻辑（按最小闭环列清单）

### 1) `services/order-service`

**新增/改动：**

- Spanner DDL：
  - `Orders(orderId PK, clientOrderId UNIQUE, userId, status, createdAt, updatedAt)`
  - `OrderItems(orderId, skuId, qty, priceCents?, PK=(orderId, skuId))`
- 业务逻辑（核心）：
  - `createOrder(userId, clientOrderId, items)`
    - 幂等：同一个 `clientOrderId` 重复请求返回同一单（用 unique index + 查回）
    - 创建订单初始 `CREATED`
    - 同步调用 inventory reserve 成功后，把订单改成 `RESERVED`
    - 如果 reserve 失败：订单直接 `CANCELED` 或者创建失败不落库（两种都行，MVP 选你更好实现的）
  - `confirmPayment(orderId)`：仅允许 `RESERVED -> PAID`
  - `cancelOrder(orderId)`：仅允许 `CREATED/RESERVED -> CANCELED`，并调用 inventory release（对 RESERVED 才 release）
- 状态机“硬约束”（很重要，最小也要做）：
  - 用条件更新保证合法流转：
     `UPDATE Orders SET status='PAID' WHERE orderId=? AND status='RESERVED'`
     通过 rowCount 判断是否成功

### 2) `services/inventory-service`

**新增/改动：**

- Spanner DDL：
  - `Inventory(skuId PK, available INT64, updatedAt)`
  - 强烈建议加一个最小的幂等表：`Reservations(orderId, skuId, qty, PK=(orderId, skuId))`
- 业务逻辑：
  - `reserve(orderId, items)`（RW 事务）
    - 先查 Reservations：如果已存在（说明重试），直接返回成功（幂等）
    - 对每个 sku 做条件扣减：`available >= qty` 才能扣（扣不动就整体失败）
    - 插入 Reservations
  - `release(orderId)`（RW 事务）
    - 查 Reservations，把 qty 加回 Inventory，再删除 Reservations
    - 重复 release 也要安全（幂等：没 reservation 就直接成功）

> 你说“用 Spanner 事务/条件更新保证不超卖”——对，这里就是核心亮点。

### 3) `services/payment-service`

**最小做法：**

- 一个 stub 接口：`POST /payments/{orderId}/confirm?result=success|fail`
- 它可以：
  - success：调用 order-service 的 `confirmPayment`
  - fail：调用 order-service 的 `cancelOrder`
- 也可以更简单：payment-service 只返回 success/fail，order-service 自己处理状态（更干净）

### 4) `libs/clients`（或 order-service 里临时 client）

- order-service 需要调用 inventory-service
- payment-service（如果保留）需要调用 order-service
- MVP 阶段不用搞很花：你现在已经有 catalog client 的模式，照着复用就行

### 5) `libs/contracts`

- Milestone 6 **不强依赖** contracts（因为你说先同步闭环，不上 Kafka）
- 你后面上 Change Streams/Kafka 再加订单事件也不迟

------

### 我建议你 Milestone 6 的“验收脚本”（最小但很像 demo）

你列的验收项已经很好了，我再补两条让它更像“大厂可演示”：

- **重复下单不会多扣库存**：同 clientOrderId 调 2 次，返回同 orderId，库存只扣一次
- **并发不超卖**：两个人同时买最后 1 件，一个成功一个失败（这是你最硬的亮点）



# Kafka用什么版本

## 如果用 KRaft mode，用哪个版本？

✅ 推荐 1（优先）：**Apache Kafka 4.1.1（KRaft）**

- 这是官方当前 stable。[Apache Kafka](https://kafka.apache.org/documentation)
- 你想对齐未来主流（尤其是你做分布式电商项目、面试要讲架构演进），选它最合理。



## 作为“项目交付优先”的建议

- **短期（不打断你节奏）**：继续用你现在的 `cp-kafka:7.6.1` 跑功能、做更多 milestone。
- **中期（你准备面试/项目成熟）**：再切到 **Kafka 4.1.1 KRaft**（同时把 Zookeeper 从 compose 里删掉），你项目会更“现代”、更像真实生产趋势。

如果你愿意，我可以按你现在的 compose 风格，告诉你**最小改动**的切换策略（删 ZK、单节点 combined controller/broker、端口保持 29092 不变），保证你服务侧配置基本不用动。

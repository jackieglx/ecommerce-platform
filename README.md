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





## Milestone 5：幂等 + 去重

等 Milestone 4 跑通后，你就会立刻遇到真实问题：
 **Kafka 至少一次投递 → 重复消费**，以及“同一个 sku 反复被索引”。

所以 Milestone 5 可以做成你想要的亮点：

### 目标

为 search-service 的索引消费实现**幂等处理**（AOP + Redis），并把它变成你项目的可复用能力。

### 具体内容（最小版本）

- `libs/common-redis`：Redis 操作封装
- `libs/common-web` 或新 `libs/common-idempotency`：`@Idempotent` + AOP
- search-service 的 Kafka consumer 方法上加 `@Idempotent(key = "skuId")`
- key 设计：`idem:search:index:{eventId 或 skuId}:{version}`，TTL 例如 10~30 分钟

### 验收

- 连续发送同一条消息 10 次，ES 最终只有一次更新/无异常
- consumer 重启后不会重复造成错误（最多重复写 ES，但逻辑稳定）

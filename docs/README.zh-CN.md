# 高并发分布式电商秒杀系统（微服务）

- 📄 English: [README](README.md)
- 📄 中文: 本文
- 🏗️ 高层设计（High Level Design）: [docs/high-level-design.md](docs/high-level-design.md)

## 简介

一个面向**秒杀/抢购**场景设计的 **高并发、分布式电商平台后端**：通过 **Redis + Lua 原子扣减**、**事件驱动异步下单**、**Outbox + Spanner Change Streams** 的可靠消息投递、以及 **订单超时自动取消与库存回补**，实现“**快响应、不超卖、可恢复、最终一致**”。

---

## 背景与我的贡献

这个项目源于分布式系统课程期末的自主选题。我对高并发场景下的技术挑战和架构权衡很感兴趣，因此提议并主导了“秒杀平台”方向。我不仅参与了前期的架构讨论，也**独立完成了核心后端链路的实现**，包括：

- 秒杀下单主链路：Redis 库存、Lua 原子扣减、Stream 事件、转发到 Kafka、Order Service 落库
- 订单超时取消：Outbox + Change Streams 可靠发布、Redis 延迟队列（双 ZSET + owner）、故障恢复
- 读多写少的商品详情二级缓存：Caffeine L1 + Redis L2、SingleFlight 防击穿、缓存失效广播
- 搜索与 7 天热销：ES 索引、流式聚合 7 天销量、批量更新 ES 支持排序/榜单

---

## 设计目标

> 目标是支撑秒杀瞬时流量与电商高并发读写的典型压力，并以**最终一致性**为主要一致性模型。

- **下单（Flash Sale）瞬时峰值**：≥ 50,000 QPS（以压测/模拟流量为目标）
- **商品查询/访问**：百万级 QPS（读多写少、依赖缓存体系支撑）
- **一致性目标**：关键链路不超卖；跨服务以事件驱动实现**最终一致性**；消费者幂等保证“至少一次投递”下不产生副作用

---

## 秒杀场景的核心挑战与应对

### 挑战 1：既要极快响应，又要防止超卖
**策略：缩短同步链路 + 前置拦截无效请求 + 原子扣减库存**

- **链路尽可能短**：秒杀请求在内存/Redis 层完成判断与扣减，减少数据库参与
- **早拦截**：库存不足/重复下单/无效幂等请求尽早返回，节省 CPU、网络、下游资源
- **原子性**：Lua 脚本把“校验 + 扣减 + 记录 + 发布事件”合并为单次原子操作，避免并发竞态导致超卖

### 挑战 2：超时未支付订单要可靠取消并释放库存
**策略：可靠发布超时事件 + 延迟队列可恢复执行 + 条件更新 + 释放库存事件**

- 订单创建时用 **Outbox** 保证“落库成功 → 事件最终可发布”
- 超时执行用 **Redis 延迟队列（双 ZSET + owner）** 保证可恢复、可重新领取
- 库存回补通过 Kafka 事件驱动，由库存服务幂等处理

### 挑战 3：高并发读下的缓存与一致性权衡
**策略：二级缓存 + 防击穿 + 旁路缓存（cache-aside）+ 分布式失效通知**

- L1：Caffeine（本地）尽可能把热点请求留在本机
- L2：Redis（共享）降低 DB 压力
- 防击穿：SingleFlight 合并同 key 回源
- 一致性：写路径以 Change Streams 驱动缓存失效；必要时用“版本写入”或“延时双删”缩短旧值窗口

---

## 架构概览（高层）

```
Client
  │
  ▼
Gateway (鉴权/路由/限流)
  │
  ├─ Flash Sale API ───────────────► Inventory Service (Redis + Lua)
  │                                   │
  │                                   ├─ Redis Stream (flashsale events)
  │                                   ▼
  │                              Stream Forwarder
  │                                   │ (Kafka ack 后再 XACK)
  │                                   ▼
  │                               Kafka Topics
  │                                   │
  │                                   ▼
  │                              Order Service (Spanner)
  │                                   │
  │                                   ├─ Outbox (same txn)
  │                                   ▼
  │                       Spanner Change Streams → Kafka
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

## 技术栈

- **Java 21**
- **Spring Boot 3.5.x / Spring Cloud 2025.x**
- **Google Cloud Spanner**：分布式关系型数据库 & Change Streams
- **Redis**：秒杀库存、延迟队列、幂等、缓存、Pub/Sub
- **Kafka**：服务间事件驱动通信
- **Elasticsearch**：商品搜索与排序
- **Apache Spark Streaming**：7 天销量流式聚合

---

## 核心模块与服务

- **Gateway Service**：统一入口（路由/鉴权/限流等）
- **Catalog Service**：商品与 SKU 管理，发布商品变更事件
- **Search Service**：ES 搜索、过滤、排序；消费变更事件实时更新索引
- **Inventory Service**：库存与秒杀；Redis Lua 原子扣减；库存释放消费
- **Order Service**：订单生命周期；创建/取消/状态流转；Outbox 写入
- **Payment Service**：支付状态更新；支付成功事件发布
- **Cart/User/Notification Service**：购物车/用户/通知等基础能力（可按需扩展）
- **Jobs / sales-7d-streaming-job**：Spark Streaming 计算 `sales_7d` 并回写 ES
- **libs/**：公共库（contracts、kafka、redis、idempotency 等）

---

## 关键设计细节

### 1) 秒杀下单：Redis Lua + Stream 事件 + Kafka 异步落库

**目标：** 快响应 + 不超卖 + 事件不丢

**实现要点：**
- 秒杀库存放在 Redis（预热/提前写入）
- 请求进入后，通过 Lua 脚本原子完成：
  1. 校验库存是否足够
  2. 校验“一人一单”（例如用 Redis Set 记录已购用户）
  3. 幂等检查（Idempotency Key）
  4. 扣减库存
  5. 写入 Redis Stream 一条“扣减/下单意图”事件
- Lua 成功返回后即可立刻响应前端，引导支付；把“写 DB、下游流程”全部异步化
- **可靠转发：**转发器阻塞读取 Stream，把事件写入 Kafka；只有当 Kafka 写入成功后才对 Stream 进行 `XACK`（避免“已 ack 但 Kafka 未收到”的丢失）
- **消费端幂等：**所有消费者通过自定义 Spring AOP 注解实现幂等（即使重复消费也不会产生重复副作用），从而可以容忍至少一次投递

---

### 2) 订单超时取消：Outbox + Change Streams + 可恢复延迟队列

**目标：** 超时订单一定会被取消；库存一定会被释放；系统崩溃可恢复

#### 2.1 订单创建与超时事件可靠发布（避免“写库成功但发消息前挂掉”）
订单服务创建订单时，在**同一个数据库事务**内完成：
1. 插入订单记录（Order）
2. 插入一条 `NEW` 状态的超时取消事件（Outbox）

事务提交后，通过 **Spanner Change Streams** 订阅 Outbox 表的变更，并借助 Connector 将 Outbox 事件推送到 Kafka，交由 `OrderTimeoutProcessor` 消费。

#### 2.2 延迟队列：双 ZSET + owner（可恢复）
- `ready ZSET`：待处理任务（member=orderId，score=expireAt）
- `processing ZSET`：正在处理的任务（member=orderId，score=leaseTime）
- `owner HASH`：任务所有权（field=orderId，value=token(uuid + threadId)）

领取任务时将 `ready → processing` **原子迁移**并写入 owner；避免多个 worker 重复处理同一订单。

#### 2.3 任务执行与库存释放
`OrderTimeoutProcessor` 执行取消逻辑：
- 在 Spanner 中对订单做**条件更新**（例如仅当状态仍为 `PENDING_PAYMENT` 才能更新为 `CANCELLED`）
  - 若已支付/已取消：不回补库存，直接结束
  - 若更新成功：在**同一事务**内写入一条“释放库存”事件到 Outbox（包含 `orderId/skuId/qty` 等）
- 释放库存事件通过 Change Streams → Kafka 投递给库存服务，库存服务幂等回补 Redis 库存

#### 2.4 故障恢复
如果 worker 领取任务后崩溃，会出现 `orderId` 已从 ready 移走但没完成处理。
- 使用 `processing ZSET` 做恢复：定期扫描超时 lease 的任务，把其放回 `ready ZSET`，并清理 owner，允许其他线程重新领取执行

---

### 3) 高并发读：二级缓存（Caffeine L1 + Redis L2）与一致性策略

**场景：** 商品详情/库存展示等读多写少接口，需要顶住高并发读。

#### 3.1 读路径：L1→L2→DB，并用 SingleFlight 防击穿
- L1 miss，L2 hit：从 Redis 回填当前实例的 L1
- L1 miss，L2 miss：回源 DB
  - 为防止击穿 DB：同一实例内对同一 `skuId` 使用 **SingleFlight** 合并请求  
    将“同 key 并发回源”从 N 次压缩为 1 次

**SingleFlight 实现思路：**
- 每个实例维护 `ConcurrentHashMap<skuId, CompletableFuture<CacheValue>>` 作为 inFlight 表
- `putIfAbsent` 成功者为 leader：负责查 DB + 回填 L2/L1 + `future.complete(result)`
- 其余为 follower：等待 leader 的 future 结果，不直接访问 DB
- 若 follower 等待超时：best-effort 清理 inFlight，避免异常 leader 导致长期阻塞

> 如果未来需要跨实例进一步防击穿，可在 L2 miss 时增加分布式锁，但会带来额外延迟与复杂性。

#### 3.2 写路径：cache-aside + 失效广播（最终一致）
- Cache Invalidation Service 订阅 Spanner Change Streams 的商品变更事件
- 收到变更后：
  1. 删除 Redis（L2）
  2. 通过 Redis Pub/Sub 广播失效消息，各实例清理本地 Caffeine（L1）

#### 3.3 防止旧值回填扩散：版本写入 / 延时双删
并发读写时可能出现“旧值在失效后又回填到 Redis”的问题，可用两种方案：
- **方案 A：Redis 写入带版本**（推荐）
  - `SET` 前在 Lua 中比较 `newVersion > oldVersion`，否则丢弃回填
- **方案 B：延时双删**（工程兜底）
  - 先删一次，延迟一段时间再删一次；窗口取值需要权衡

---

### 4) 搜索与 7 天热销：Kafka → Spark Streaming → Elasticsearch

**目标：** 搜索支持按 `sales_7d` 排序，并展示“7 天热销榜”。

#### 4.1 为什么是 7 天
- 比日榜更稳定，能反映趋势
- 比月榜/总榜存储与计算成本更低，适合快速上线

#### 4.2 计算与写入策略（避免高频写 ES）
- 支付成功后，通过 CDC / Outbox 将 `OrderPaid` 写入 Kafka
- Spark Streaming 每 10 秒批量消费事件，按 `skuId` 聚合
- 使用 `flatMapGroupsWithState` 为每个 `skuId` 维护状态（滑动 7 天窗口）
- 为避免逐单写 ES 影响检索性能：**每小时批量更新**有变化（dirty）的 SKU 的 `sales_7d` 字段

#### 4.3 状态设计（每个 sku）
- `bucketVal[168]`：168 个小时桶（7 天 * 24），记录该小时增量
- `slotHour[168]`：该桶当前对应的小时编号（判断是否复用/过期）
- `sum7d`：近 7 天总和（避免每次遍历 168 桶）
- `dirty`：本输出周期是否变化（只写有变化的 sku）
- `lastEmitHour / lastAdvancedHour`：用于整点输出与窗口推进

#### 4.4 为什么用 ProcessingTimeTimeout
- 一方面清理不活跃 key，防止 state 无限增长
- 另一方面用于整点定时回调：即使没有新事件，也能在整点推进窗口并刷新 ES

---

## 仓库结构

```
ecommerce-platform/
├── services/          # 微服务
│   ├── gateway-service/
│   ├── catalog-service/
│   ├── search-service/
│   ├── inventory-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── cart-service/
│   ├── user-service/
│   └── notification-service/
├── jobs/              # 批处理/流式作业
│   └── sales-7d-streaming-job/
├── libs/              # 公共库（contracts、idempotency、kafka/redis 封装等）
└── infra/             # 基础设施（本地/容器化等）
```

---



## 设计文档

- 高层设计文档：`docs/high-level-design.md`

---

# 高并发分布式电商秒杀系统（微服务）

- 📄 English: [README](README.md)
- 📄 中文: 本文
- 🏗️ 高层设计（High Level Design）: [docs/high-level-design.md](docs/high-level-design.md)

## 简介

一个面向**秒杀/抢购**场景设计的 **高并发、分布式电商平台后端**：通过 **Redis + Lua 原子扣减**、**事件驱动异步下单**、**定时轮询 Outbox 表后发送 Kafka** 的可靠消息投递、以及 **订单超时自动取消与库存回补**，实现“**快响应、不超卖、可恢复、最终一致**”。

---

## 背景与我的贡献

这个项目源于分布式系统课程期末的自主选题。我对高并发场景下的技术挑战和架构权衡很感兴趣，因此提议并主导了“秒杀平台”方向。我不仅参与了前期的架构讨论，也**独立完成了核心后端链路的实现**，包括：

- 秒杀下单主链路：Redis 库存、Lua 原子扣减、Stream 事件、转发到 Kafka、Order Service 落库
- 订单超时取消：Outbox 定时轮询可靠发布、Redis 延迟队列（双 ZSET + owner）、故障恢复
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
- 一致性：写路径提交后删除 L2、L1，并通过 Redis Pub/Sub 广播失效；必要时可用“版本写入”或“延时双删”缩短旧值窗口

---

## 架构概览（高层）

```
Client
  │
  ▼
直接访问服务端点（gateway-service 当前为占位模块）
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
  │                       Outbox 定时轮询发布器 → Kafka
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
- **Google Cloud Spanner**：分布式关系型数据库
- **Redis**：秒杀库存、延迟队列、幂等、缓存、Pub/Sub
- **Kafka**：服务间事件驱动通信
- **Elasticsearch**：商品搜索与排序
- **Apache Spark Streaming**：7 天销量流式聚合

---

## 核心模块与服务

- **Gateway Service**：当前仅输出 `Hello, World!` 的占位模块，尚未实现路由、鉴权或限流
- **Catalog Service**：商品与 SKU 管理，发布商品变更事件
- **Search Service**：ES 搜索、过滤、排序；消费变更事件实时更新索引
- **Inventory Service**：库存与秒杀；Redis Lua 原子扣减；库存释放消费
- **Order Service**：订单生命周期；创建/取消/状态流转；Outbox 写入
- **Payment Service**：支付状态更新；支付成功事件发布
- **Cart/User/Notification Service**：购物车/用户/通知等基础能力（可按需扩展）
- **Jobs / sales-7d-streaming-job**：Spark Streaming 计算 `sales_7d` 并回写 ES
- **libs/**：公共库（contracts、kafka、redis、idempotency 等）

---

## 本地开发：宿主机 Java + Compose 依赖

当前支持的本地模式是：Docker 只运行 Spanner Emulator、Kafka、Redis、Elasticsearch；Catalog、Search、Inventory、Order、Payment 五个核心服务以宿主机 Java 进程运行。仓库没有服务 Dockerfile，Compose 也不会启动 Java 服务。

前置条件：Docker Desktop/Engine（含 Compose）、Java 21、Maven、`curl`（或 PowerShell）。Windows 使用 `.ps1` 启动脚本时还需要 Bash 用于 Kafka topic 初始化，安装 Git for Windows 即可；不需要云账号或外部密钥。

一键启动：

```bash
./scripts/run-local.sh                 # Linux/macOS
pwsh -File .\scripts\run-local.ps1    # Windows PowerShell
```

初始化 20 个固定本地演示商品和库存，然后启动前端：

```powershell
.\scripts\seed-demo-data.ps1
cd frontend
Copy-Item .env.example .env
npm install
npm run dev
```

Linux/macOS 使用 `./scripts/seed-demo-data.sh` 和 `cp frontend/.env.example frontend/.env`。默认 `.env.example` 已包含全部固定 demo SKU ID，前端通过真实 Catalog batch API 查询商品，不需要手工填写 SKU。

脚本会启动依赖、幂等初始化 Spanner DDL、创建 Kafka topics、构建项目，然后以 `SPRING_PROFILES_ACTIVE=local` 启动五个核心服务。仅启动依赖可用 `--deps-only` / `-Mode deps-only`；之后在另一个终端用 `--services-only` / `-Mode services-only` 启动 Java 服务。日志和 PID 文件位于 `.local/`。

容器内与宿主机地址不能混用：Compose 工具使用 `SPANNER_EMULATOR_HOST=spanner:9010`、Kafka `kafka:9092`；宿主机 Java 服务必须使用 `SPANNER_EMULATOR_HOST=localhost:9010`、`KAFKA_BOOTSTRAP_SERVERS=localhost:29092`。完整宿主机变量见 `infra/local/host.env.example`。必须启用 `local` profile，因为 SKU 写入接口和 Inventory 的 seed/admin 接口受该 profile 限制，未启用时会返回 404。

最小 smoke test：

```bash
./scripts/smoke-local.sh
pwsh -File .\scripts\smoke-local.ps1
```

测试会检查五个 `/actuator/health`，创建 SKU、初始化库存（同时预热价格）、预留一次秒杀库存，等待 Kafka 驱动的 Order 创建，提交支付，并通过真实 `GET /api/v1/orders/{orderId}` 等待 Order 为 `PAID`。

停止：`./scripts/stop-local.sh` 或 `pwsh -File .\scripts\stop-local.ps1`。若需要破坏性地清空本地数据，运行 `infra/local/scripts/reset.sh`（会删除 Docker volumes）。`infra/k8s`、`infra/gcp`、`infra/ci` 目前只是计划，仓库中尚不存在。

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

### 2) 订单超时取消：Outbox 定时轮询 + 可恢复延迟队列

**目标：** 超时订单一定会被取消；库存一定会被释放；系统崩溃可恢复

#### 2.1 订单创建与超时事件可靠发布（避免“写库成功但发消息前挂掉”）
订单服务创建订单时，在**同一个数据库事务**内完成：
1. 插入订单记录（Order）
2. 插入一条 `NEW` 状态的超时取消事件（Outbox）

事务提交后，由服务内的定时 Outbox 发布器领取待发送记录、发送至 Kafka，并将记录标记为已发送或可重试；本地环境未包含 Spanner Change Streams Connector。随后由 `OrderTimeoutProcessor` 消费并安排超时处理。

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
- 释放库存事件由同一 Outbox 定时发布器发送到 Kafka，库存服务幂等回补 Redis 库存

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
- Catalog 的写服务在数据库事务提交后执行缓存旁路失效：
  1. 先删除 Redis（L2），再删除当前实例的 Caffeine（L1）
  2. 通过 Redis Pub/Sub 广播 SKU 失效消息，各实例订阅后清理本地 Caffeine（L1）

当前没有独立的 Cache Invalidation Service，也没有基于 Change Streams 的缓存失效组件。

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
- 支付成功后，由 Payment Outbox 定时发布器将 `OrderPaid` 写入 Kafka
- Spark Streaming 每分钟触发一个 processing-time 微批次，按 `skuId` 聚合
- 使用 `flatMapGroupsWithState` 为每个 `skuId` 维护状态（滑动 7 天窗口）
- 有变化的 SKU 每个微批次最多写回一次 ES；空闲 SKU 每小时被唤醒，以同步订单自然过期造成的销量变化

#### 4.3 状态设计（每个 sku）
- `bucketVal[168]`：168 个小时桶（7 天 * 24），记录该小时增量
- `slotHour[168]`：该桶当前对应的小时编号（判断是否复用/过期）
- `sum7d`：近 7 天总和
- `dirty`：本输出周期是否变化（只写有变化的 sku）
- `lastEmitHour / lastAdvancedHour`：记录最近一次输出与窗口推进，便于诊断

#### 4.4 为什么用 ProcessingTimeTimeout
- 每小时唤醒仍在窗口内的 SKU。每次唤醒扫描固定的 168 个桶，清除当前 7 天窗口外的桶，并将变化后的值（包括 0）回写 ES。
- 最后一个桶过期后，先持久化 0，再删除该 SKU 的 state；不会扫描数据库或 ES 索引。性能成本为每小时每个活跃 SKU 168 次简单桶检查。

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

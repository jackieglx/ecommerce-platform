# Inventory 秒杀入口本地压测

本文档用于测量本地单机环境中 Inventory 秒杀预留接口的入口承压能力，并校验 Redis Stream → Kafka → Order 异步链路最终是否收敛。它不是生产容量报告，也不能用于宣称系统已经达到 50K 或 500K QPS。

## 测试对象与指标口径

真实接口：

```http
POST http://localhost:8082/api/v1/flashsale/reservations
X-User-Id: load-{RUN_ID}-vu{VU}-iter{ITERATION}
Idempotency-Key: load-{RUN_ID}-vu{VU}-iter{ITERATION}
Content-Type: application/json

{"skuId":"loadtest-hot-sku-001","qty":1}
```

接口对 `RESERVED`、`SOLD_OUT`、`DUPLICATE` 和 `FAILED` 都返回 HTTP 200。HTTP 200 只表示 HTTP 调用成功，只有响应体中的 `status=RESERVED` 且包含非空 `orderId` 才表示业务预留成功。

需要分别理解：

- attempt QPS：每秒尝试抢购的请求数。有限库存售罄后，大部分请求会走更短的 `SOLD_OUT` 路径；这是入口承压的主要指标。
- RESERVED QPS：每秒真正扣减库存、写入 Redis Stream 并进入异步链路的请求数。

不能把包含大量快速 `SOLD_OUT` 的整体 p95 写成“成功下单 p95”。脚本分别输出整体、`RESERVED` 和 `SOLD_OUT` 的 p50/p95/p99。

## 前置条件

1. Docker Desktop 或 Docker Engine/Compose。
2. Java、Maven 和仓库现有本地启动依赖。
3. k6。安装方式以 [Grafana k6 官方文档](https://grafana.com/docs/k6/latest/set-up/install-k6/) 为准；安装后执行：

   ```bash
   k6 version
   ```

4. Bash 脚本还需要 `curl` 和 `jq`。PowerShell 脚本不需要 jq。
5. 后端必须以 `local` profile 启动，因为 Catalog 管理接口和 Inventory seed/查询接口只在 local profile 下存在。

启动五个核心后端：

```powershell
.\scripts\run-local.ps1
```

或：

```bash
./scripts/run-local.sh
```

确认至少 Catalog、Inventory、Order 以及 Kafka、Redis、Spanner emulator 正常。数据准备脚本会额外检查 Catalog 8080 和 Inventory 8082 的 health。

## 测试 Profile

### limited_stock（默认）

使用 `INITIAL_STOCK` 指定有限库存。请求总数可以远大于库存：

- 最多约 `INITIAL_STOCK` 个请求应为 `RESERVED`。
- 其余正常请求应为 `SOLD_OUT`。
- 适合测量入口 attempt QPS、售罄速度和是否超卖。

### success_capacity

用于测量成功路径和下游吞吐。准备库存为：

```text
ceil(TARGET_RPS × DURATION_SECONDS × 1.2)
```

当前脚本支持生成该配置，但未经明确确认不得实际运行此 profile。

## SKU 模式

### single（默认）

所有请求访问 `loadtest-hot-sku-001`，用于测量单热点库存 key、Lua、buyer set、单个 Redis Stream 分片和 Inventory HTTP 入口。

该 SKU 按项目的 Java hash 规则映射到 Stream 分片 `04`。

### sharded

请求按全局迭代编号均匀分配到 8 个固定 SKU，`TARGET_RPS` 是 8 个 SKU 的总 QPS：

| SKU | Java hashCode | Stream 分片 |
|---|---:|---:|
| `loadtest-shard-00-001` | 1643092712 | 00 |
| `loadtest-shard-01-001` | 1644016233 | 01 |
| `loadtest-shard-02-001` | 1644939754 | 02 |
| `loadtest-shard-03-001` | 1645863275 | 03 |
| `loadtest-shard-04-001` | 1646786796 | 04 |
| `loadtest-shard-05-001` | 1647710317 | 05 |
| `loadtest-shard-06-001` | 1648633838 | 06 |
| `loadtest-shard-07-001` | 1649557359 | 07 |

准备脚本会用与 `FlashSaleKeyGenerator` 等价的 Java `String.hashCode()` 和 `floorMod(hash, 8)` 算法再次验证映射，不依赖名称猜测。未经明确确认不得实际运行 sharded 压测。

`loadtest-warmup-sku` 映射到分片 `05`，仅供未来独立预热使用。基线脚本当前不自动预热，因此不会消耗正式测试 SKU 的库存，也不会把预热计入正式结果。

## 环境变量

| 变量 | 默认值 | 含义 |
|---|---|---|
| `BASE_URL` | `http://localhost:8082` | Inventory 地址 |
| `RUN_ID` | 无，必填 | 本轮唯一标识，3–48 位字母、数字、`_`、`-` |
| `TEST_PROFILE` | `limited_stock` | `limited_stock` 或 `success_capacity` |
| `SKU_MODE` | `single` | `single` 或 `sharded` |
| `TARGET_RPS` | `10` | constant-arrival-rate 的目标总 QPS |
| `DURATION` | `10s` | 计时窗口，如 `30s`、`2m` |
| `INITIAL_STOCK` | `100` | limited_stock 的总库存 |
| `PRE_ALLOCATED_VUS` | `20` | k6 预分配 VU |
| `MAX_VUS` | `100` | k6 最大 VU；不足时产生 dropped iterations |

默认 QPS 和持续时间刻意保持较低。每轮必须使用新的 `RUN_ID`。每个请求的 userId 和 Idempotency-Key 都包含 RUN_ID、VU 和该 VU 的迭代号；只改变幂等键而复用 userId 会触发 Redis buyer set 的 `DUPLICATE`。

`orderId` 始终读取真实后端响应，k6 不生成或伪造订单号。

## 执行流程

完整流程必须严格按“准备 → 记录基线 → k6 → 结果校验”执行。不要在 k6 运行期间重新 seed。

### Windows PowerShell

以下是安全的低流量示例，不是授权执行 100 QPS：

```powershell
$env:RUN_ID = 'example-low-001'
$env:TEST_PROFILE = 'limited_stock'
$env:SKU_MODE = 'single'
$env:TARGET_RPS = '10'
$env:DURATION = '10s'
$env:INITIAL_STOCK = '100'
$env:PRE_ALLOCATED_VUS = '20'
$env:MAX_VUS = '100'

.\scripts\prepare-load-data.ps1
.\scripts\verify-load-results.ps1 -Phase before

k6 run `
  -e RUN_ID=$env:RUN_ID `
  -e TEST_PROFILE=$env:TEST_PROFILE `
  -e SKU_MODE=$env:SKU_MODE `
  -e TARGET_RPS=$env:TARGET_RPS `
  -e DURATION=$env:DURATION `
  -e INITIAL_STOCK=$env:INITIAL_STOCK `
  -e PRE_ALLOCATED_VUS=$env:PRE_ALLOCATED_VUS `
  -e MAX_VUS=$env:MAX_VUS `
  perf/k6/inventory-reservation.js

.\scripts\verify-load-results.ps1 -Phase after
```

### Linux/macOS Bash

```bash
export RUN_ID=example-low-001
export TEST_PROFILE=limited_stock
export SKU_MODE=single
export TARGET_RPS=10
export DURATION=10s
export INITIAL_STOCK=100
export PRE_ALLOCATED_VUS=20
export MAX_VUS=100

./scripts/prepare-load-data.sh
./scripts/verify-load-results.sh before

k6 run \
  -e RUN_ID="$RUN_ID" \
  -e TEST_PROFILE="$TEST_PROFILE" \
  -e SKU_MODE="$SKU_MODE" \
  -e TARGET_RPS="$TARGET_RPS" \
  -e DURATION="$DURATION" \
  -e INITIAL_STOCK="$INITIAL_STOCK" \
  -e PRE_ALLOCATED_VUS="$PRE_ALLOCATED_VUS" \
  -e MAX_VUS="$MAX_VUS" \
  perf/k6/inventory-reservation.js

./scripts/verify-load-results.sh after
```

## 数据准备行为

准备脚本通过真实 local API 创建或更新以下专用 SKU：

- `loadtest-hot-sku-001`
- `loadtest-warmup-sku`
- `loadtest-shard-00-001` 至 `loadtest-shard-07-001`

随后调用 `/internal/inventory/seed`，并验证：

- Catalog 查询返回真实 SKU 和价格。
- Inventory 只读接口返回预期可用库存。
- Redis `fs:price` hash 中存在 Catalog 的真实价格，证明价格预热完成。
- 每个 SKU 的 Java hash 和 Stream 分片映射正确。

脚本可重复执行，但会重置这些专用 loadtest SKU 的库存。它不会读取、修改或清理 20 个 demo SKU。重复执行不会清理 buyer set，因此仍必须使用新的 RUN_ID。

## 正确性与异步收敛校验

`before` 阶段记录：

- Redis 和 Spanner 初始库存。
- Orders 总数及本 RUN_ID 的订单数。
- Redis Stream length、group lag、pending。
- Kafka `order-flashsale-v2` consumer lag。
- 测试开始时间。

`after` 阶段读取 k6 机器结果并轮询：

- Redis 库存不能为负。
- Spanner 可用库存不能为负。
- limited_stock 且请求数覆盖库存时，`RESERVED=INITIAL_STOCK` 且 Redis 最终库存为 0。
- `DUPLICATE=0`、`FAILED=0`，解析、未知状态和响应契约错误为 0。
- Redis Stream group lag/pending 和 Kafka consumer lag 收敛到 0。
- 通过 `Orders.UserId` 的 `load-{RUN_ID}-` 前缀做 Spanner 只读计数，不逐笔调用 Order API。
- 新增订单数最终等于 k6 的 `RESERVED` 数量。

默认最多等待 180 秒，可通过 `DRAIN_TIMEOUT_SECONDS` 和 `POLL_INTERVAL_SECONDS` 调整。drain time 是压测结束至上述条件首次全部满足的时间；超时会明确失败，不会伪造收敛。

结果保存在 `perf/results/{RUN_ID}/`：

- `parameters.json`
- `baseline.json`
- `k6-summary.txt`
- `k6-summary.json`
- `k6-result.json`
- `verification.json`
- `report.md`

该目录已被 git 忽略，避免提交大型运行结果。需要保留小型报告时，应人工复制或显式选择文件。

## 如何判断目标 QPS 是否达到

k6 使用 `constant-arrival-rate`，不是固定 VU 模型。结果同时报告：

- 目标 QPS。
- `http_reqs` 的实际发出 QPS。
- `iterations` 的实际完成 QPS。
- RESERVED QPS。
- dropped iterations。
- `insufficientVUs`，即 dropped iterations 是否大于 0。

如果 dropped iterations 不为 0，应先检查本机资源和延迟，再评估是否增加 VU；不能把目标 QPS 当作实际达到的 QPS。

## 售罄、超卖和延迟解释

脚本把第一次观测到 `SOLD_OUT` 的时间作为近似售罄时间。最终正确性以 Redis/Spanner 库存和 RESERVED 数量为准。

超卖判定包括：

- Redis 最终库存小于 0。
- Spanner 最终可用库存小于 0。
- RESERVED 数量大于准备库存。

库存充足时 `RESERVED` 会执行 Lua 扣减、buyer set、snapshot 和 XADD；售罄后 `SOLD_OUT` 会走 Redis GET 快速返回。因此两类延迟必须分开阅读。

## 停止测试

在 k6 终端按 `Ctrl+C`。中止后不要把不完整结果当作容量结论；如需重跑，应使用新的 RUN_ID，重新执行 prepare 和 before。不要在测试运行中执行 seed，也不要删除 volume 来“修复”结果。

## 本地结果的限制

笔记本同时运行 k6、Java 服务、Docker、Redis、Kafka、Spanner emulator 和其他进程，CPU、内存、Docker Desktop VM、网络转发和磁盘会互相竞争。本地结果只代表该机器在该时刻的整机表现，不能直接外推生产 50K/500K QPS，也不能替代多机、真实云数据库或生产级 Kafka 的容量测试。

## 受控的首轮 100 QPS

约定首轮参数为：

```text
RUN_ID=<每轮新的值>
TEST_PROFILE=limited_stock
SKU_MODE=single
TARGET_RPS=100
DURATION=30s
INITIAL_STOCK=1000
PRE_ALLOCATED_VUS=50
MAX_VUS=200
```

预计最多约 3,000 次请求、1,000 次 RESERVED 和约 2,000 次 SOLD_OUT。该轮会写入约 1,000 笔订单以及 1,000 个 buyer set 成员。必须先汇报完整命令和影响并取得明确确认后才能运行。

只有 100 QPS 的请求速率、错误率、库存、独立延迟、积压和订单数全部满足成功标准后，才能另行申请执行 500 QPS；脚本不会自动提高负载。

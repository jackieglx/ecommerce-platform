# Frontend

先启动本地后端栈并初始化固定演示商品：

```powershell
.\scripts\run-local.ps1
.\scripts\seed-demo-data.ps1
```

然后在 `frontend` 目录运行：

```bash
npm install
cp .env.example .env
npm run dev
```

Vite 将 `/catalog-api`、`/inventory-api`、`/order-api` 与 `/payment-api` 分别代理到本机 Catalog (8080)、Inventory (8082)、Order (8083) 与 Payment (8084)。`.env.example` 已包含种子脚本创建的 20 个固定 SKU ID；Catalog 仍然只有真实的 `POST /api/v1/skus/batch`，没有虚构商品列表接口。

前端使用两个独立路由：

- `/`：只展示 Catalog 商品卡片、真实 Inventory 可用库存和数量选择。点击“立即抢购”会调用真实 Inventory 预留 API，只有响应状态为 `RESERVED` 且包含真实 `orderId` 时才跳转。`DUPLICATE` 不会跳转，因为当前 buyers 数据只记录用户是否购买，不能可靠反查原订单号。
- `/orders/{orderId}`：订单详情和模拟支付页。页面直接从 URL 取得 `orderId`，通过真实 Order API 恢复状态，并通过订单中的 `skuId` 查询 Catalog 商品摘要；因此刷新页面不依赖商品页的内存状态。

订单页在 `PENDING_PAYMENT` 时显示“模拟支付”。点击后调用真实 Payment API，但 Payment 成功响应仅表示请求被受理，UI 不会据此显示 `PAID`。页面每 2 秒查询 `GET /api/v1/orders/{orderId}`；异步订单创建期间的 404 最多短暂重试 10 秒，持续 404 会明确显示“订单未创建/订单不存在”。其他非终态最长轮询 60 秒；`PAID`、`CANCELLED`、`FAILED` 会立即停止。`DUPLICATE` 留在商品页并提示“你已预留过此商品，请查看原订单或选择其他商品”。

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

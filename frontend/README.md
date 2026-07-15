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

页面从 Inventory 预留响应取得真实 `orderId`，并轮询只读的 `GET /api/v1/orders/{orderId}`。异步订单创建尚未完成时产生的 404 会继续重试；每 2 秒轮询一次，在 `PAID`、`CANCELLED`、`FAILED` 时停止，60 秒后也会停止并显示提示。Payment 成功响应仅表示 Payment 接受了请求，UI 不会据此显示 `PAID`。

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

# E-commerce Platform

- 📄 English: [README](docs/README.en.md)
- 📄 中文: [README.zh-CN](docs/README.zh-CN.md)
- 🏗️ High Level Design: [docs/high-level-design.md](docs/high-level-design.md)
- 🚀 Local development: [English](docs/README.en.md#local-development-host-java--compose-dependencies) / [中文](docs/README.zh-CN.md#本地开发宿主机-java--compose-依赖)

## 本地 Demo Quick Start（Windows）

```powershell
.\scripts\run-local.ps1
.\scripts\seed-demo-data.ps1
cd frontend
Copy-Item .env.example .env
npm install
npm run dev
```

`run-local.ps1` 启动基础依赖和五个核心 Java 后端，不启动前端。`seed-demo-data.ps1` 通过真实的 Catalog 与 Inventory local API 幂等创建 20 个固定演示 SKU 和库存。复制默认 `.env.example` 后无需手工填写 SKU ID，页面会通过 Catalog batch API 展示这些真实商品。商品页预留成功后会跳转到 `/orders/{orderId}`；订单页可刷新恢复状态，并以真实 Order API 的结果决定是否显示 `PAID`。

Linux/macOS 使用 `./scripts/run-local.sh`、`./scripts/seed-demo-data.sh` 和 `cp frontend/.env.example frontend/.env`。完整说明见 [当前项目运行说明](docs/当前项目运行说明.md)。

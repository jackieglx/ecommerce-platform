



```bash
# 你想“彻底重置”本地依赖环境（比如数据乱了、版本不一致、初始化脚本需要重跑）
docker compose -f infra/local/docker-compose.yml down -v

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
```


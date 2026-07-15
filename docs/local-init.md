# 本地初始化

推荐的本地模式是“Compose 启动依赖，Java 服务在宿主机启动”。不要直接复用旧的硬编码工作目录或手动执行 `spanner-tools` 默认 entrypoint。

```bash
./scripts/run-local.sh                 # Linux/macOS
pwsh -File .\scripts\run-local.ps1    # Windows PowerShell
./scripts/smoke-local.sh
```

脚本会以正确的 entrypoint 初始化 Spanner、创建 Kafka topics，并以 `local` profile 启动 Catalog、Search、Inventory、Order、Payment。容器使用 `spanner:9010`/`kafka:9092`，宿主机 Java 使用 `localhost:9010`/`localhost:29092`；详细说明见中英文 README。

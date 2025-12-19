**Docker Desktop 重启/容器重建后**：跑

```bash
docker compose exec spanner-tools bash -lc "cd /workspace/infra/local/spanner && bash ./bootstrap.sh"

```





**只是改代码重启 catalog-service**：不需要跑 init 脚本（只要 emulator 没重启、DB 还在）



```bash
curl.exe -X POST "http://localhost:8080/internal/skus" -H "Content-Type: application/json" -d '{"skuId":"sku_1","productId":"prod_1","title":"iPhone 15","status":"ACTIVE","priceCents":129900,"currency":"USD"}'

curl.exe "http://localhost:8080/products/sku_1"
```




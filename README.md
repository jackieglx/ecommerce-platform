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
docker compose -f infra/local/docker-compose.yml up -d

Invoke-RestMethod http://localhost:9200

cd E:\ecommerce-platform\infra\local\elasticsearch\init

powershell -ExecutionPolicy Bypass -File .\01-create-index.ps1


cd E:\ecommerce-platform

docker compose -f infra/local/docker-compose.yml exec spanner-tools bash -lc "cd /workspace/infra/local/spanner/init && ./00-create-instance-db.sh && ./01-apply-ddl.sh"


$CATALOG="http://localhost:8080"

curl.exe -sS -X POST "http://localhost:8080/internal/skus" `
  -H "Content-Type: application/json" `
  --data-binary "@infra/local/sku.json"
  


curl.exe -i -X POST "http://localhost:8081/internal/search/indexSkus" `
  -H "Content-Type: application/json" `
  --data-binary "@infra/local/index-request.json"
  

curl.exe -sS "http://localhost:8081/search?q=iphone"
curl.exe -sS "http://localhost:8081/search?q=iphone&brand=Apple"
curl.exe -sS "http://localhost:8081/search?q=iphone&minPrice=100000&maxPrice=150000"
curl.exe -sS "http://localhost:8081/search?q=iphone&page=1&size=20"

```


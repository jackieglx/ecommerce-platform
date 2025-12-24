param(
  [string]$SkuId = "sku_1",
  [string]$UserId = "u-1",
  [int]$InitStock = 5,
  [string]$BaseUrl = "http://localhost:8082",
  [string]$OrderId = ""   # 可选：传固定 orderId 用于测试 duplicate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---- helpers ----
function RedisCli {
  param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
  # 直接 docker exec 更稳定（容器名就是 docker ps 里的 redis）
  & docker exec -i redis redis-cli @Args
}

function WriteUtf8NoBomFile([string]$path, [string]$content) {
  [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
}

# ---- 1) variables ----
if ([string]::IsNullOrWhiteSpace($OrderId)) {
  $OrderId = "o-fs-" + (Get-Date -Format "HHmmss")
}

$stockKey  = "fs:stock:{fs}:$SkuId"
$buyersKey = "fs:buyers:{fs}:$SkuId"
$orderKey  = "fs:order:{fs}:$OrderId"
$streamKey = "fs:outbox:{fs}:flashsale-reserved"

Write-Host "SkuId=$SkuId  UserId=$UserId  OrderId=$OrderId"
Write-Host "Keys:"
Write-Host "  stock : $stockKey"
Write-Host "  buyers: $buyersKey"
Write-Host "  order : $orderKey"
Write-Host "  stream: $streamKey"
Write-Host ""

# ---- 2) quick health check ----
Write-Host "Checking Redis..."
$ping = RedisCli PING
Write-Host "PING => $ping"
Write-Host ""

# ---- 3) prepare redis ----
Write-Host "Preparing Redis..."
Write-Host "SET stockKey..."
RedisCli SET $stockKey $InitStock | Out-Host

Write-Host "DEL buyers/order/stream..."
RedisCli DEL $buyersKey | Out-Host
RedisCli DEL $orderKey  | Out-Host
RedisCli DEL $streamKey | Out-Host
Write-Host ""

# ---- 4) build request file ----
$body = @{ orderId=$OrderId; skuId=$SkuId; userId=$UserId; qty=1 } | ConvertTo-Json -Compress
$reqFile = Join-Path $PWD "flashsale-reserve.json"
WriteUtf8NoBomFile -path $reqFile -content $body

Write-Host "Request body:"
Write-Host $body
Write-Host "Request file: $reqFile"
Write-Host ""

# ---- 5) call API ----
Write-Host "Calling reserve API..."
& curl.exe -i -X POST "$BaseUrl/internal/flashsale/reserve" `
  -H "Content-Type: application/json" `
  --data-binary "@$reqFile"

Write-Host ""
Write-Host "Checking Redis state (expected: stock=$($InitStock-1), buyer=1, order exists=1, stream len>=1) ..."
Write-Host ""

# ---- 6) verify ----
Write-Host "stock TYPE/GET:"
RedisCli TYPE $stockKey | Out-Host
RedisCli GET  $stockKey | Out-Host
Write-Host ""

Write-Host "buyers SISMEMBER:"
RedisCli SISMEMBER $buyersKey $UserId | Out-Host
Write-Host ""

Write-Host "order EXISTS:"
RedisCli EXISTS $orderKey | Out-Host
Write-Host ""

Write-Host "stream TYPE/XLEN:"
RedisCli TYPE $streamKey | Out-Host
RedisCli XLEN $streamKey | Out-Host
Write-Host ""

# ---- 7) diagnostics (limited scan, avoid long run) ----
Write-Host "Diagnose outbox keys (SCAN fs:outbox* , first 50 only):"
$keys = RedisCli --scan --pattern "fs:outbox*" | Select-Object -First 50
if ($keys.Count -eq 0) {
  Write-Host "(no keys matched fs:outbox*)"
} else {
  $keys | ForEach-Object { Write-Host $_ }
}
Write-Host ""

Write-Host "Done."

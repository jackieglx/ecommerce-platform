param(
  [string]$SkuId = "sku_1",
  [string]$UserId = "u-1",
  [int]$InitStock = 5,
  [string]$BaseUrl = "http://localhost:8082",
  [string]$OrderId = "",   # 可选：传固定 orderId 用于测试 duplicate
  [int]$Repeat = 2,

  # --- FlashSaleRequest 必填字段 ---
  [long]$PriceCents = 100,
  [string]$Currency = "USD",

  # outbox stream + consumer group（避免你 DEL 掉导致 NOGROUP）
  [string]$StreamKey = "fs:outbox:{fs}:flashsale-reserved",
  [string]$Group = "fs-publisher"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ---- helpers ----
function RedisCli {
  param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
  & docker exec -i redis redis-cli @Args
}

function WriteUtf8NoBomFile([string]$path, [string]$content) {
  [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
}

function CurlJsonPost([string]$url, [string]$reqFile, [string]$respFile) {
  # body 写到 respFile；返回 http code
  $code = & curl.exe -s -o $respFile -w "%{http_code}" -X POST $url `
    -H "Content-Type: application/json" `
    --data-binary "@$reqFile"
  return $code
}

function EnsureStreamAndGroup([string]$streamKey, [string]$group) {
  # MKSTREAM: stream 不存在也创建
  # BUSYGROUP: group 已存在，忽略
  & docker exec -i redis redis-cli XGROUP CREATE $streamKey $group 0 MKSTREAM 2>$null | Out-Null
}

function ClearStreamKeepGroup([string]$streamKey) {
  # 清空 stream 但不 DEL key（尽量保留 group）
  & docker exec -i redis redis-cli XTRIM $streamKey MAXLEN 0 2>$null | Out-Null
}

function ReadJsonFile([string]$path) {
  if (-not (Test-Path $path)) { return $null }
  $raw = Get-Content $path -Raw
  if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
  try { return $raw | ConvertFrom-Json }
  catch { return $null }
}

# ---- 1) variables ----
if ([string]::IsNullOrWhiteSpace($OrderId)) {
  $OrderId = "o-fs-" + (Get-Date -Format "HHmmss")
}

$stockKey  = "fs:stock:{fs}:$SkuId"
$buyersKey = "fs:buyers:{fs}:$SkuId"
$orderKey  = "fs:order:{fs}:$OrderId"
$streamKey = $StreamKey

Write-Host "SkuId=$SkuId  UserId=$UserId  OrderId=$OrderId"
Write-Host "Keys:"
Write-Host "  stock : $stockKey"
Write-Host "  buyers: $buyersKey"
Write-Host "  order : $orderKey"
Write-Host "  stream: $streamKey"
Write-Host "  group : $Group"
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

Write-Host "DEL buyers/order..."
RedisCli DEL $buyersKey | Out-Host
RedisCli DEL $orderKey  | Out-Host

Write-Host "Ensure outbox stream/group (do NOT DEL stream)..."
EnsureStreamAndGroup -streamKey $streamKey -group $Group
Write-Host "Clear stream entries (keep key/group)..."
ClearStreamKeepGroup -streamKey $streamKey
Write-Host ""

# ---- 4) build request file ----
# 这里必须匹配 InternalFlashSaleController.FlashSaleRequest 的字段
$bodyObj = @{
  orderId     = $OrderId
  skuId       = $SkuId
  userId      = $UserId
  qty         = 1
  priceCents  = $PriceCents
  currency    = $Currency
}
$body = $bodyObj | ConvertTo-Json -Compress

$reqFile = Join-Path $PWD "flashsale-reserve.json"
WriteUtf8NoBomFile -path $reqFile -content $body

Write-Host "Request body:"
Write-Host $body
Write-Host "Request file: $reqFile"
Write-Host ""

# ---- 5) baseline ----
$stockBefore = [int](RedisCli GET $stockKey)

$lenBefore = 0
try { $lenBefore = [int](RedisCli XLEN $streamKey) } catch { $lenBefore = 0 }

Write-Host "Baseline:"
Write-Host "Stock before=$stockBefore"
Write-Host "Stream XLEN before=$lenBefore"
Write-Host ""

# ---- 6) call API ----
Write-Host "Calling reserve API x$Repeat with SAME OrderId (idempotency test)..."
Write-Host ""

$results = @()

for ($i = 1; $i -le $Repeat; $i++) {
  Write-Host "---- Call #$i ----"
  $respFile = Join-Path $PWD ("resp-" + $i + ".json")

  $code = CurlJsonPost -url "$BaseUrl/internal/flashsale/reserve" -reqFile $reqFile -respFile $respFile
  $respText = ""
  if (Test-Path $respFile) { $respText = Get-Content $respFile -Raw }

  Write-Host "HTTP $code"
  if ($respText) { Write-Host $respText }

  if ([int]$code -lt 200 -or [int]$code -ge 300) {
    throw "Reserve API failed on call #$i (HTTP $code). Response above."
  }

  $obj = ReadJsonFile $respFile
  if ($null -eq $obj) {
    throw "Call #$i returned 2xx but response is not valid JSON: $respText"
  }
  $results += $obj
  Write-Host ""
}

# ---- 7) validate behavior from API ----
# 第一次必须成功（否则没法验证“成功场景的幂等”）
if (-not $results[0].success) {
  throw "Call #1 did not succeed. success=$($results[0].success) duplicate=$($results[0].duplicate) insufficient=$($results[0].insufficient)"
}
# 第二次必须 duplicate=true（同 orderId 重复提交）
if (-not $results[1].duplicate) {
  throw "Call #2 should be duplicate=true but got duplicate=$($results[1].duplicate). Full response: $(($results[1] | ConvertTo-Json -Compress))"
}

Write-Host "All calls returned 2xx and API behavior looks correct (call#1 success, call#2 duplicate)."
Write-Host "Now checking Redis side effects..."
Write-Host ""

# ---- 8) verify Redis side effects (success case) ----
$stockAfter = [int](RedisCli GET $stockKey)

$lenAfter = 0
try { $lenAfter = [int](RedisCli XLEN $streamKey) } catch { $lenAfter = 0 }

Write-Host "Stock: before=$stockBefore  after=$stockAfter  (expected after = before - 1)"
Write-Host "Stream XLEN: before=$lenBefore  after=$lenAfter  (expected after = before + 1)"
Write-Host ""

if ($stockAfter -ne ($stockBefore - 1)) {
  throw "Idempotency FAILED: stock side effect not exactly once. before=$stockBefore after=$stockAfter"
}
if ($lenAfter -ne ($lenBefore + 1)) {
  throw "Idempotency FAILED: stream side effect not exactly once. before=$lenBefore after=$lenAfter"
}

Write-Host "Idempotency OK: repeated calls did not cause extra side effects."
Write-Host ""

# ---- 9) details ----
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

Write-Host "Diagnose outbox keys (SCAN fs:outbox* , first 50 only):"
$keys = RedisCli --scan --pattern "fs:outbox*" | Select-Object -First 50
if ($keys.Count -eq 0) { Write-Host "(no keys matched fs:outbox*)" }
else { $keys | ForEach-Object { Write-Host $_ } }
Write-Host ""

Write-Host "Done."

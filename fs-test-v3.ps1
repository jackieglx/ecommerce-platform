
param(
  [string]$SkuId = "sku_1",
  [long]$Qty = 1,
  [string]$UserId = "u-1",
  [int]$InitStock = 5,
  [string]$BaseUrl = "http://localhost:8082",

  # 为空则自动生成；同一次脚本运行会用同一个 key 做重复请求
  [string]$IdempotencyKey = "",

  # Redis keys（按你项目的命名习惯）
  [string]$StockKey  = "",   # 为空则按 fs:stock:{fs}:<sku> 拼
  [string]$BuyersKey = "",   # 为空则按 fs:buyers:{fs}:<sku> 拼
  [string]$StreamKey = "fs:outbox:{fs}:flashsale-reserved",

  # outbox consumer group（避免你把 stream 删掉导致 NOGROUP）
  [string]$OutboxGroup = "fs-publisher",

  # 是否额外做“同 key 不同 payload”冲突测试
  [switch]$PayloadMismatchTest,
  [string]$MismatchSkuId = "sku_2",
  [long]$MismatchQty = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function RedisCli {
  param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
  & docker exec -i redis redis-cli @Args
}

function WriteUtf8NoBomFile([string]$path, [string]$content) {
  [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
}

function CurlPostJson([string]$url, [string]$reqFile, [hashtable]$headers) {
  $headerArgs = @()
  foreach ($k in $headers.Keys) {
    # 用 -f 格式化，避免 "$k:" 触发 PowerShell 解析器把 ":" 当成变量作用域符号
    $headerArgs += @("-H", ("{0}: {1}" -f $k, $headers[$k]))
  }

  $respFile = Join-Path $PSScriptRoot "fs-resp.json"
  if (Test-Path $respFile) { Remove-Item $respFile -Force }

  # curl 返回 http code；body 写到 fs-resp.json
  $code = & curl.exe -s -o $respFile -w "%{http_code}" -X POST $url @headerArgs --data-binary "@$reqFile"
  $body = ""
  if (Test-Path $respFile) {
    $body = Get-Content -Raw $respFile
  }
  return @{ code = [int]$code; body = $body }
}

function ParseJsonOrNull([string]$s) {
  if ([string]::IsNullOrWhiteSpace($s)) { return $null }
  try { return $s | ConvertFrom-Json } catch { return $null }
}

function SafeRedisInt([scriptblock]$sb, [int]$defaultValue = 0) {
  try {
    $v = & $sb
    if ($null -eq $v -or [string]::IsNullOrWhiteSpace("$v")) { return $defaultValue }
    return [int]$v
  } catch {
    return $defaultValue
  }
}

# ---- 0) defaults ----
if ([string]::IsNullOrWhiteSpace($IdempotencyKey)) {
  $IdempotencyKey = "idem-fs-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + (Get-Random -Maximum 1000)
}
if ([string]::IsNullOrWhiteSpace($StockKey))  { $StockKey  = "fs:stock:{fs}:$SkuId" }
if ([string]::IsNullOrWhiteSpace($BuyersKey)) { $BuyersKey = "fs:buyers:{fs}:$SkuId" }

Write-Host "SkuId=$SkuId  Qty=$Qty  UserId=$UserId  InitStock=$InitStock"
Write-Host "IdempotencyKey=$IdempotencyKey"
Write-Host "Keys:"
Write-Host "  stock : $StockKey"
Write-Host "  buyers: $BuyersKey"
Write-Host "  stream: $StreamKey"
Write-Host "  group : $OutboxGroup"
Write-Host ""

# ---- 1) check redis ----
Write-Host "Checking Redis..."
$ping = RedisCli PING
Write-Host "PING => $ping"
Write-Host ""

# ---- 2) prepare redis baseline ----
Write-Host "Preparing Redis..."
RedisCli SET $StockKey $InitStock | Out-Null
RedisCli DEL $BuyersKey | Out-Null

# ⚠️ 不要 DEL $StreamKey：你服务端有 scheduled 的 XREADGROUP，删掉会触发 NOGROUP。
# 这里做“确保 group 存在”（如果已存在会 BUSYGROUP，我们忽略错误输出）
& docker exec -i redis redis-cli XGROUP CREATE $StreamKey $OutboxGroup 0 MKSTREAM 2>$null | Out-Null

Write-Host "OK (stock reset / buyers cleared; outbox group ensured)"
Write-Host ""

# Baseline metrics（stream 不存在时 XLEN 会报错 -> 当 0）
$stockBefore = SafeRedisInt { RedisCli GET $StockKey } 0
$streamLenBefore = SafeRedisInt { RedisCli XLEN $StreamKey } 0

# ---- 3) request ----
$reqObj = @{ skuId = $SkuId; qty = $Qty }
$reqJson = ($reqObj | ConvertTo-Json -Compress)
$reqFile = Join-Path $PSScriptRoot "flashsale-reserve-v2.json"
WriteUtf8NoBomFile $reqFile $reqJson

$url = "$BaseUrl/api/v1/flashsale/reservations"
$headers = @{
  "Content-Type"    = "application/json"
  "Idempotency-Key" = $IdempotencyKey
  "X-User-Id"       = $UserId
}

Write-Host "POST $url"
Write-Host "Request body: $reqJson"
Write-Host ""

# ---- 4) call #1 ----
Write-Host "---- Call #1 ----"
$r1 = CurlPostJson $url $reqFile $headers
Write-Host "HTTP $($r1.code)"
Write-Host "Resp1: $($r1.body)"
Write-Host ""

if ($r1.code -lt 200 -or $r1.code -ge 300) {
  # HTTP 错误直接退出（但我们依然打印了 body，方便你对照服务端日志）
  throw "Call #1 failed (HTTP $($r1.code)). Server returned error response above."
}

$j1 = ParseJsonOrNull $r1.body
if (-not $j1) { throw "Call #1 response is not valid JSON" }

# ---- 5) call #2 (same key, same payload) ----
Write-Host "---- Call #2 (same Idempotency-Key) ----"
$r2 = CurlPostJson $url $reqFile $headers
Write-Host "HTTP $($r2.code)"
Write-Host "Resp2: $($r2.body)"
Write-Host ""

if ($r2.code -lt 200 -or $r2.code -ge 300) {
  throw "Call #2 failed (HTTP $($r2.code)). Server returned error response above."
}

$j2 = ParseJsonOrNull $r2.body
if (-not $j2) { throw "Call #2 response is not valid JSON" }

# ---- 6) assertions: response stable (best-effort) ----
# 不同服务返回字段可能不一样；所以这里做“尽量比对”，字段不存在就跳过
$stable = $true
if ($j1.PSObject.Properties.Match("status").Count -gt 0 -and $j2.PSObject.Properties.Match("status").Count -gt 0) {
  if ($j1.status -ne $j2.status) { $stable = $false }
}
if ($j1.PSObject.Properties.Match("orderId").Count -gt 0 -and $j2.PSObject.Properties.Match("orderId").Count -gt 0) {
  if ($j1.orderId -ne $j2.orderId) { $stable = $false }
}
if ($j1.PSObject.Properties.Match("reservationExpiresAt").Count -gt 0 -and $j2.PSObject.Properties.Match("reservationExpiresAt").Count -gt 0) {
  if ($j1.reservationExpiresAt -ne $j2.reservationExpiresAt) { $stable = $false }
}

if (-not $stable) {
  throw "Idempotency FAILED: response differs across same Idempotency-Key + same payload"
}
Write-Host "✅ Response stable across duplicate requests (same key + same payload)."
Write-Host ""

# ---- 7) side effects: stock/stream only once ----
$stockAfter = SafeRedisInt { RedisCli GET $StockKey } 0
$streamLenAfter = SafeRedisInt { RedisCli XLEN $StreamKey } 0

Write-Host "Stock:  before=$stockBefore  after=$stockAfter"
Write-Host "Stream: before=$streamLenBefore  after=$streamLenAfter"

# 如果你的接口返回 status=RESERVED，才严格断言；否则只输出观测值
if ($j1.PSObject.Properties.Match("status").Count -gt 0 -and $j1.status -eq "RESERVED") {
  if ($stockAfter -ne ($stockBefore - 1)) { throw "Expected stock decrease by 1, got $stockBefore -> $stockAfter" }
  if ($streamLenAfter -ne ($streamLenBefore + 1)) { throw "Expected stream XLEN +1, got $streamLenBefore -> $streamLenAfter" }
  Write-Host "✅ Side effects happened exactly once."
} else {
  Write-Host "ℹ️ status not 'RESERVED' (or no status field). Skipping strict side-effect assertions."
}
Write-Host ""

# ---- 8) payload mismatch (same key, different body) ----
if ($PayloadMismatchTest) {
  Write-Host "---- Call #3 (SAME Idempotency-Key, DIFFERENT payload) ----"
  $reqObj2 = @{ skuId = $MismatchSkuId; qty = $MismatchQty }
  $reqJson2 = ($reqObj2 | ConvertTo-Json -Compress)
  $reqFile2 = Join-Path $PSScriptRoot "flashsale-reserve-mismatch.json"
  WriteUtf8NoBomFile $reqFile2 $reqJson2
  Write-Host "Mismatch body: $reqJson2"

  $r3 = CurlPostJson $url $reqFile2 $headers
  Write-Host "HTTP $($r3.code)"
  Write-Host "Resp3: $($r3.body)"
  Write-Host ""

  Write-Host "ℹ️ 预期：服务端应该拒绝（例如 409 / 422），否则说明 Idempotency-Key 没有绑定 payload。"
}

Write-Host "DONE."

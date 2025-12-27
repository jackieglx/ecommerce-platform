param(
  [string]$SkuId = "sku_1",
  [long]$Qty = 1,
  [string]$UserId = "u-1",
  [int]$InitStock = 5,
  [string]$BaseUrl = "http://localhost:8082",

  # 为空则自动生成；同一次脚本运行会用同一个 key 做重复请求
  [string]$IdempotencyKey = "",

  # Redis keys（按你之前脚本的命名习惯）
  [string]$StockKey  = "",   # 为空则按 fs:stock:{fs}:<sku> 拼
  [string]$BuyersKey = "",   # 为空则按 fs:buyers:{fs}:<sku> 拼
  [string]$StreamKey = "fs:outbox:{fs}:flashsale-reserved",

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

function ParseJsonOrNull([string]$s) {
  if ([string]::IsNullOrWhiteSpace($s)) { return $null }
  try { return $s | ConvertFrom-Json } catch { return $null }
}

function GetProp([object]$obj, [string]$name) {
  if ($null -eq $obj) { return $null }
  $p = $obj.PSObject.Properties[$name]
  if ($null -eq $p) { return $null }
  return $p.Value
}

function IsHttpSuccess([int]$code) {
  return ($code -ge 200 -and $code -lt 300)
}

function CurlPostJson([string]$url, [string]$reqFile, [hashtable]$headers) {
  $headerArgs = @()
  foreach ($k in $headers.Keys) {
    # 关键修复：PowerShell 会把 "$k: xxx" 里的 $k: 误当成 “drive 变量”
    $headerArgs += @("-H", "$($k): $($headers[$k])")
  }

  $respFile = Join-Path $PSScriptRoot "fs-resp.json"
  $errFile  = Join-Path $PSScriptRoot "fs-curl-err.txt"
  if (Test-Path $respFile) { Remove-Item $respFile -Force }
  if (Test-Path $errFile)  { Remove-Item $errFile  -Force }

  # curl 输出：http code -> stdout；body -> fs-resp.json；错误 -> fs-curl-err.txt
  $codeStr = & curl.exe -sS -o $respFile -w "%{http_code}" -X POST $url @headerArgs --data-binary "@$reqFile" 2> $errFile
  $exitCode = $LASTEXITCODE

  $body = if (Test-Path $respFile) { Get-Content -Raw $respFile } else { "" }
  $err  = if (Test-Path $errFile)  { Get-Content -Raw $errFile  } else { "" }

  $code = 0
  try { $code = [int]$codeStr } catch { $code = 0 }

  return @{ exitCode = $exitCode; code = $code; body = $body; err = $err }
}

# ---- 0) defaults ----
if ([string]::IsNullOrWhiteSpace($IdempotencyKey)) {
  $rand = Get-Random -Minimum 100 -Maximum 999
  $IdempotencyKey = "idem-fs-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-$rand"
}
if ([string]::IsNullOrWhiteSpace($StockKey))  { $StockKey  = "fs:stock:{fs}:$SkuId" }
if ([string]::IsNullOrWhiteSpace($BuyersKey)) { $BuyersKey = "fs:buyers:{fs}:$SkuId" }

Write-Host "SkuId=$SkuId  Qty=$Qty  UserId=$UserId  InitStock=$InitStock"
Write-Host "IdempotencyKey=$IdempotencyKey"
Write-Host "Keys:"
Write-Host "  stock : $StockKey"
Write-Host "  buyers: $BuyersKey"
Write-Host "  stream: $StreamKey"
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
RedisCli DEL $StreamKey | Out-Null
Write-Host "OK (stock reset / buyers+stream cleared)"
Write-Host ""

# Baseline metrics
$stockBefore = [int](RedisCli GET $StockKey)
$streamLenBefore = [int](RedisCli XLEN $StreamKey)

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
if ($r1.exitCode -ne 0) {
  Write-Host "curl exitCode=$($r1.exitCode)"
  if (-not [string]::IsNullOrWhiteSpace($r1.err)) { Write-Host $r1.err }
  throw "Call #1 failed to reach server (curl error). Check if service is running and BaseUrl is correct."
}
Write-Host "HTTP $($r1.code)"
Write-Host "Resp1: $($r1.body)"
if (-not (IsHttpSuccess $r1.code)) {
  throw "Call #1 failed (HTTP $($r1.code)). Server returned error response above."
}
$j1 = ParseJsonOrNull $r1.body
if (-not $j1) { throw "Call #1 response is not valid JSON." }
Write-Host ""

# ---- 5) call #2 (same key, same payload) ----
Write-Host "---- Call #2 (same Idempotency-Key) ----"
$r2 = CurlPostJson $url $reqFile $headers
if ($r2.exitCode -ne 0) {
  Write-Host "curl exitCode=$($r2.exitCode)"
  if (-not [string]::IsNullOrWhiteSpace($r2.err)) { Write-Host $r2.err }
  throw "Call #2 failed to reach server (curl error)."
}
Write-Host "HTTP $($r2.code)"
Write-Host "Resp2: $($r2.body)"
if (-not (IsHttpSuccess $r2.code)) {
  throw "Call #2 failed (HTTP $($r2.code)). Server returned error response above."
}
$j2 = ParseJsonOrNull $r2.body
if (-not $j2) { throw "Call #2 response is not valid JSON." }
Write-Host ""

# ---- 6) assertions: response stable ----
$st1 = GetProp $j1 "status"
$st2 = GetProp $j2 "status"
$oid1 = GetProp $j1 "orderId"
$oid2 = GetProp $j2 "orderId"
$exp1 = GetProp $j1 "reservationExpiresAt"
$exp2 = GetProp $j2 "reservationExpiresAt"

if ($null -eq $st1 -or $null -eq $oid1 -or $null -eq $exp1) {
  throw "Call #1 JSON missing expected fields: status/orderId/reservationExpiresAt. Body=$($r1.body)"
}
if ($null -eq $st2 -or $null -eq $oid2 -or $null -eq $exp2) {
  throw "Call #2 JSON missing expected fields: status/orderId/reservationExpiresAt. Body=$($r2.body)"
}

if ($st1 -ne $st2 -or $oid1 -ne $oid2 -or $exp1 -ne $exp2) {
  throw "Idempotency FAILED: response differs across same Idempotency-Key + same payload"
}
Write-Host "✅ Response stable across duplicate requests (same key + same payload)."
Write-Host ""

# ---- 7) side effects: stock/stream only once ----
$stockAfter = [int](RedisCli GET $StockKey)
$streamLenAfter = [int](RedisCli XLEN $StreamKey)

Write-Host "Stock:  before=$stockBefore  after=$stockAfter"
Write-Host "Stream: before=$streamLenBefore  after=$streamLenAfter"

# 只在第一次成功 RESERVED 的时候期待副作用
if ($st1 -in @("RESERVED", "SUCCESS", "OK")) {
  if ($stockAfter -ne ($stockBefore - 1)) { throw "Expected stock decrease by 1, got $stockBefore -> $stockAfter" }
  if ($streamLenAfter -ne ($streamLenBefore + 1)) { throw "Expected stream XLEN +1, got $streamLenBefore -> $streamLenAfter" }
  Write-Host "✅ Side effects happened exactly once."
} else {
  Write-Host "ℹ️ Status=$st1, side effects may be 0 by design; skipped strict side-effect assertion."
}
Write-Host ""

# ---- 8) payload mismatch test (optional) ----
if ($PayloadMismatchTest) {
  $badObj = @{ skuId = $MismatchSkuId; qty = $MismatchQty }
  $badJson = ($badObj | ConvertTo-Json -Compress)
  $badFile = Join-Path $PSScriptRoot "flashsale-reserve-v2-mismatch.json"
  WriteUtf8NoBomFile $badFile $badJson

  Write-Host "---- Call #3 (same key, DIFFERENT payload) ----"
  Write-Host "Bad body: $badJson"
  $r3 = CurlPostJson $url $badFile $headers

  if ($r3.exitCode -ne 0) {
    Write-Host "curl exitCode=$($r3.exitCode)"
    if (-not [string]::IsNullOrWhiteSpace($r3.err)) { Write-Host $r3.err }
    throw "Call #3 failed to reach server (curl error)."
  }

  Write-Host "HTTP $($r3.code)"
  Write-Host "Resp3: $($r3.body)"

  if ($r3.code -ne 409) {
    throw "Expected HTTP 409 on payload mismatch, got $($r3.code)"
  }

  # verify no extra side effects
  $stockAfter3 = [int](RedisCli GET $StockKey)
  $streamLenAfter3 = [int](RedisCli XLEN $StreamKey)
  if ($stockAfter3 -ne $stockAfter -or $streamLenAfter3 -ne $streamLenAfter) {
    throw "Payload mismatch should have NO side effects, but stock/stream changed"
  }
  Write-Host "✅ Payload mismatch correctly returns 409 and causes no side effects."
}

Write-Host ""
Write-Host "ALL DONE."

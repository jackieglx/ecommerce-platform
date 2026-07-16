[CmdletBinding()]
param(
    [string]$RunId = $env:RUN_ID,
    [string]$TestProfile = $(if ($env:TEST_PROFILE) { $env:TEST_PROFILE } else { 'limited_stock' }),
    [string]$SkuMode = $(if ($env:SKU_MODE) { $env:SKU_MODE } else { 'single' }),
    [int]$TargetRps = $(if ($env:TARGET_RPS) { [int]$env:TARGET_RPS } else { 10 }),
    [string]$Duration = $(if ($env:DURATION) { $env:DURATION } else { '10s' }),
    [long]$InitialStock = $(if ($env:INITIAL_STOCK) { [long]$env:INITIAL_STOCK } else { 100 }),
    [string]$CatalogBaseUrl = $(if ($env:CATALOG_BASE_URL) { $env:CATALOG_BASE_URL } else { 'http://localhost:8080' }),
    [string]$InventoryBaseUrl = $(if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8082' })
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeFile = Join-Path $Root 'infra/local/docker-compose.yml'
$ComposeEnv = Join-Path $Root 'infra/local/.env'
$SingleSku = 'loadtest-hot-sku-001'
$WarmupSku = 'loadtest-warmup-sku'
$ShardedSkus = 0..7 | ForEach-Object { 'loadtest-shard-{0:d2}-001' -f $_ }

function Assert-Configuration {
    if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$') { throw 'RUN_ID is required and must use 3-48 letters, digits, underscore, or hyphen.' }
    if ($TestProfile -notin @('limited_stock', 'success_capacity')) { throw "Unsupported TEST_PROFILE: $TestProfile" }
    if ($SkuMode -notin @('single', 'sharded')) { throw "Unsupported SKU_MODE: $SkuMode" }
    if ($TargetRps -lt 1) { throw 'TARGET_RPS must be positive.' }
    if ($InitialStock -lt 1) { throw 'INITIAL_STOCK must be positive.' }
}

function ConvertTo-DurationSeconds([string]$Value) {
    if ($Value -notmatch '^(\d+)(s|m|h)$') { throw "DURATION must look like 30s, 2m, or 1h; got '$Value'." }
    $number = [long]$Matches[1]
    if ($number -le 0) { throw 'DURATION must be positive.' }
    switch ($Matches[2]) { 'h' { return $number * 3600 }; 'm' { return $number * 60 }; default { return $number } }
}

function Get-JavaHashCode([string]$Value) {
    [long]$unsigned = 0
    foreach ($character in $Value.ToCharArray()) {
        $unsigned = (($unsigned * 31) + [int][char]$character) % 4294967296
    }
    if ($unsigned -ge 2147483648) { return $unsigned - 4294967296 }
    return $unsigned
}

function Get-StreamShard([string]$SkuId) {
    $hash = Get-JavaHashCode $SkuId
    return [int]((($hash % 8) + 8) % 8)
}

function ConvertTo-JsonBody([object]$Value) {
    return $Value | ConvertTo-Json -Compress -Depth 8
}

function Get-HttpStatus([System.Management.Automation.ErrorRecord]$ErrorRecord) {
    if ($null -ne $ErrorRecord.Exception.Response) { return [int]$ErrorRecord.Exception.Response.StatusCode }
    return 0
}

function Assert-Healthy([string]$Name, [string]$BaseUrl) {
    $health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw "$Name is not healthy: $($health.status)" }
}

function Invoke-Compose([string[]]$Arguments) {
    $baseArgs = @('compose')
    if (Test-Path $ComposeEnv) { $baseArgs += @('--env-file', $ComposeEnv) }
    $baseArgs += @('-f', $ComposeFile)
    $output = & docker @baseArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose command failed: $($Arguments -join ' ')" }
    return $output
}

function Ensure-CatalogSku([hashtable]$Product) {
    $update = @{
        title = $Product.title; status = 'ACTIVE'; brand = 'Load Test';
        priceCents = $Product.priceCents; currency = 'USD'
    }
    try {
        Invoke-RestMethod "$CatalogBaseUrl/api/v1/skus/$($Product.skuId)" -TimeoutSec 5 | Out-Null
        Invoke-RestMethod "$CatalogBaseUrl/internal/admin/v1/skus/$($Product.skuId)" -Method Patch -ContentType 'application/json' -Body (ConvertTo-JsonBody $update) | Out-Null
        Write-Host "Updated Catalog SKU $($Product.skuId)"
    } catch {
        if ((Get-HttpStatus $_) -ne 404) { throw }
        $create = $update + @{ skuId = $Product.skuId; productId = $Product.productId }
        Invoke-RestMethod "$CatalogBaseUrl/internal/admin/v1/skus" -Method Post -ContentType 'application/json' -Body (ConvertTo-JsonBody $create) | Out-Null
        Write-Host "Created Catalog SKU $($Product.skuId)"
    }
}

function Seed-And-Verify([hashtable]$Product, [long]$Stock) {
    $payload = @{ skuId = $Product.skuId; onHand = $Stock }
    Invoke-RestMethod "$InventoryBaseUrl/internal/inventory/seed" -Method Post -ContentType 'application/json' -Body (ConvertTo-JsonBody $payload) | Out-Null

    $catalog = Invoke-RestMethod "$CatalogBaseUrl/api/v1/skus/$($Product.skuId)" -TimeoutSec 5
    if ($catalog.skuId -ne $Product.skuId -or [long]$catalog.priceCents -ne [long]$Product.priceCents) {
        throw "Catalog verification failed for $($Product.skuId)."
    }
    $available = [long](Invoke-RestMethod "$InventoryBaseUrl/internal/inventory/$($Product.skuId)" -TimeoutSec 5)
    if ($available -ne $Stock) { throw "Inventory verification failed for $($Product.skuId): expected $Stock, got $available." }

    $shard = Get-StreamShard $Product.skuId
    $tag = '{A100:' + ('{0:d2}' -f $shard) + '}'
    $priceKey = "fs:price:$tag`:sku:$($Product.skuId)"
    $redisPrice = (Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', '--raw', 'HGET', $priceKey, 'priceCents') | Out-String).Trim()
    if ($redisPrice -ne [string]$Product.priceCents) { throw "Redis price preheat verification failed for $($Product.skuId): '$redisPrice'." }

    [pscustomobject]@{
        skuId = $Product.skuId
        initialStock = $Stock
        javaHashCode = Get-JavaHashCode $Product.skuId
        streamShard = '{0:d2}' -f $shard
        streamKey = "fs:stream:$tag"
        priceCents = [long]$Product.priceCents
    }
}

Assert-Configuration
$durationSeconds = ConvertTo-DurationSeconds $Duration
$plannedRequests = [long]$TargetRps * $durationSeconds
$requiredStock = if ($TestProfile -eq 'success_capacity') { [long][Math]::Ceiling($plannedRequests * 1.2) } else { $InitialStock }

$products = @(
    @{ skuId = $SingleSku; productId = 'loadtest-product-hot-001'; title = 'Load Test Hot SKU'; priceCents = 1999L },
    @{ skuId = $WarmupSku; productId = 'loadtest-product-warmup'; title = 'Load Test Warmup SKU'; priceCents = 999L }
)
foreach ($sku in $ShardedSkus) {
    $shard = Get-StreamShard $sku
    if ($shard -ne [int]($sku.Substring(15, 2))) { throw "Fixed sharded SKU mapping is invalid: $sku -> $shard" }
    $products += @{ skuId = $sku; productId = $sku.Replace('sku', 'product'); title = "Load Test Shard $('{0:d2}' -f $shard)"; priceCents = 1499L + $shard }
}

Assert-Healthy 'Catalog Service' $CatalogBaseUrl
Assert-Healthy 'Inventory Service' $InventoryBaseUrl

$results = @()
foreach ($product in $products) {
    Ensure-CatalogSku $product
    if ($product.skuId -eq $SingleSku) {
        $stock = $requiredStock
    } elseif ($product.skuId -eq $WarmupSku) {
        $stock = 100L
    } else {
        $shard = Get-StreamShard $product.skuId
        $base = [Math]::Floor($requiredStock / 8)
        $stock = [long]$base + $(if ($shard -lt ($requiredStock % 8)) { 1 } else { 0 })
    }
    $results += Seed-And-Verify $product $stock
    Write-Host "Ready SKU=$($product.skuId) stock=$stock shard=$('{0:d2}' -f (Get-StreamShard $product.skuId))"
}

$resultDirectory = Join-Path $Root "perf/results/$RunId"
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
$activeSkuIds = if ($SkuMode -eq 'single') { @($SingleSku) } else { @($ShardedSkus) }
$parameters = [ordered]@{
    runId = $RunId
    preparedAt = [DateTimeOffset]::UtcNow.ToString('o')
    testProfile = $TestProfile
    skuMode = $SkuMode
    targetRps = $TargetRps
    duration = $Duration
    durationSeconds = $durationSeconds
    plannedMaxRequests = $plannedRequests
    initialStock = $InitialStock
    preparedTestStock = $requiredStock
    preAllocatedVUs = if ($env:PRE_ALLOCATED_VUS) { [int]$env:PRE_ALLOCATED_VUS } else { 20 }
    maxVUs = if ($env:MAX_VUS) { [int]$env:MAX_VUS } else { 100 }
    baseUrl = $InventoryBaseUrl
    activeSkuIds = $activeSkuIds
    skuInventory = $results
    demoDataAffected = $false
}
$parameters | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $resultDirectory 'parameters.json')

Write-Host "Load-test data prepared. profile=$TestProfile mode=$SkuMode plannedRequests=$plannedRequests preparedTestStock=$requiredStock"
Write-Host "Parameters: $resultDirectory/parameters.json"
Write-Warning 'Preparation seeds dedicated loadtest SKUs and resets their inventory. Do not run it while a load test is active.'

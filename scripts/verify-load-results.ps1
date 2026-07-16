[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after')]
    [string]$Phase,
    [string]$RunId = $env:RUN_ID,
    [int]$DrainTimeoutSeconds = 180,
    [int]$PollIntervalSeconds = 2
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeFile = Join-Path $Root 'infra/local/docker-compose.yml'
$ComposeEnv = Join-Path $Root 'infra/local/.env'
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$') { throw 'RUN_ID is required and invalid.' }
$ResultDirectory = Join-Path $Root "perf/results/$RunId"
$ParametersPath = Join-Path $ResultDirectory 'parameters.json'
if (-not (Test-Path $ParametersPath)) { throw "Run prepare-load-data first; missing $ParametersPath" }
$Parameters = Get-Content -Raw $ParametersPath | ConvertFrom-Json

function Invoke-Compose([string[]]$Arguments) {
    $baseArgs = @('compose')
    if (Test-Path $ComposeEnv) { $baseArgs += @('--env-file', $ComposeEnv) }
    $baseArgs += @('-f', $ComposeFile)
    $output = & docker @baseArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "docker compose command failed: $($Arguments -join ' ')`n$($output -join "`n")" }
    return $output
}

function Invoke-SpannerQuery([string]$Sql) {
    $baseArgs = @('compose')
    if (Test-Path $ComposeEnv) { $baseArgs += @('--env-file', $ComposeEnv) }
    $baseArgs += @('-f', $ComposeFile)
    $queryArgs = @(
        'run', '--rm', '--no-deps', '--entrypoint', 'gcloud',
        '-e', 'SPANNER_EMULATOR_HOST=spanner:9010',
        '-e', 'CLOUDSDK_AUTH_DISABLE_CREDENTIALS=true',
        '-e', 'CLOUDSDK_CORE_PROJECT=local-project',
        '-e', 'CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER=http://spanner:9020/',
        'spanner-tools', 'spanner', 'databases', 'execute-sql', 'local-db',
        '--instance=local-instance', "--sql=$Sql", '--format=json', '--quiet'
    )
    # Compose lifecycle messages are written to stderr. Keep them out of the
    # JSON payload while still allowing them to remain visible to the caller.
    $output = & docker @baseArgs @queryArgs
    if ($LASTEXITCODE -ne 0) { throw "Spanner query failed: $Sql" }
    $text = ($output | Out-String).Trim()
    if (-not $text) { return @() }
    $payload = $text | ConvertFrom-Json
    if ($null -eq $payload.rows) { return @() }

    $fieldNames = @($payload.metadata.rowType.fields | ForEach-Object { $_.name })
    $mappedRows = @()
    foreach ($row in @($payload.rows)) {
        if ($row.Count -ne $fieldNames.Count) {
            throw "Spanner result column mismatch: fields=$($fieldNames.Count), values=$($row.Count)"
        }
        $mapped = [ordered]@{}
        for ($index = 0; $index -lt $fieldNames.Count; $index++) {
            $mapped[$fieldNames[$index]] = $row[$index]
        }
        $mappedRows += [pscustomobject]$mapped
    }
    return $mappedRows
}

function Get-RedisValue([string]$Key) {
    return ((Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', '--raw', 'GET', $Key) | Out-String).Trim())
}

function Get-SkuState([string]$SkuId) {
    $entry = @($Parameters.skuInventory | Where-Object skuId -eq $SkuId)[0]
    if (-not $entry) { throw "No prepared SKU metadata for $SkuId" }
    $tag = '{{A100:{0}}}' -f $entry.streamShard
    $redisStockKey = "fs:stock:$tag`:sku:$SkuId"
    $redisStockText = Get-RedisValue $redisStockKey
    $redisStock = if ($redisStockText -match '^-?\d+$') { [long]$redisStockText } else { throw "Invalid Redis stock for ${SkuId}: '$redisStockText'" }
    $escapedSku = $SkuId.Replace("'", "''")
    $rows = @(Invoke-SpannerQuery "SELECT OnHand AS onHand, Reserved AS reserved, OnHand - Reserved AS available FROM Inventory WHERE SkuId = '$escapedSku'")
    if ($rows.Count -ne 1) { throw "Inventory row not found for $SkuId" }
    [pscustomobject]@{
        skuId = $SkuId
        redisStock = $redisStock
        spannerOnHand = [long]$rows[0].onHand
        spannerReserved = [long]$rows[0].reserved
        spannerAvailable = [long]$rows[0].available
    }
}

function Get-OrderCounts {
    $prefix = "load-$RunId-"
    $rows = @(Invoke-SpannerQuery "SELECT COUNT(*) AS totalOrders, COUNTIF(STARTS_WITH(UserId, '$prefix')) AS runOrders FROM Orders")
    if ($rows.Count -ne 1) { throw 'Could not query Orders counts.' }
    [pscustomobject]@{ totalOrders = [long]$rows[0].totalOrders; runOrders = [long]$rows[0].runOrders }
}

function Get-RedisStreamState {
    $states = @()
    $seen = @{}
    foreach ($skuId in $Parameters.activeSkuIds) {
        $entry = @($Parameters.skuInventory | Where-Object skuId -eq $skuId)[0]
        if ($seen.ContainsKey($entry.streamKey)) { continue }
        $seen[$entry.streamKey] = $true
        $length = [long](((Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', '--raw', 'XLEN', $entry.streamKey) | Out-String).Trim()))
        $group = "fs-publisher:$($entry.streamShard)"
        $pending = 0L; $lag = 0L; $groupFound = $false
        $jsonText = (Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', '--json', 'XINFO', 'GROUPS', $entry.streamKey) | Out-String).Trim()
        if ($jsonText -and $jsonText -ne '[]') {
            foreach ($rawGroup in @($jsonText | ConvertFrom-Json)) {
                $map = @{}
                for ($i = 0; $i -lt $rawGroup.Count; $i += 2) { $map[[string]$rawGroup[$i]] = $rawGroup[$i + 1] }
                if ($map.name -eq $group) {
                    $groupFound = $true; $pending = [long]$map.pending; $lag = if ($null -eq $map.lag) { 0L } else { [long]$map.lag }
                }
            }
        }
        $states += [pscustomobject]@{ streamKey = $entry.streamKey; group = $group; groupFound = $groupFound; length = $length; pending = $pending; lag = $lag }
    }
    return $states
}

function Get-KafkaGroupState {
    $output = Invoke-Compose @('exec', '-T', 'kafka', 'kafka-consumer-groups', '--bootstrap-server', 'kafka:9092', '--describe', '--group', 'order-flashsale-v2')
    $lag = 0L; $partitions = 0
    foreach ($line in $output) {
        if ($line -match '^\s*order-flashsale-v2\s+inventory\.flashsale-reserved\.v2\s+\d+\s+\d+\s+\d+\s+(\d+)\s+') { $lag += [long]$Matches[1]; $partitions++ }
    }
    if ($partitions -eq 0) { throw "Kafka group order-flashsale-v2 returned no topic partitions.`n$($output -join "`n")" }
    [pscustomobject]@{ group = 'order-flashsale-v2'; topic = 'inventory.flashsale-reserved.v2'; partitions = $partitions; totalLag = $lag; raw = @($output) }
}

function Get-Snapshot {
    $skuStates = @($Parameters.activeSkuIds | ForEach-Object { Get-SkuState $_ })
    [ordered]@{
        capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
        skuStates = $skuStates
        totalRedisStock = [long](($skuStates | Measure-Object redisStock -Sum).Sum)
        totalSpannerAvailable = [long](($skuStates | Measure-Object spannerAvailable -Sum).Sum)
        orders = Get-OrderCounts
        redisStreams = @(Get-RedisStreamState)
        kafka = Get-KafkaGroupState
    }
}

if ($Phase -eq 'before') {
    $snapshot = Get-Snapshot
    if ($snapshot.orders.runOrders -ne 0) { throw "RUN_ID '$RunId' already has $($snapshot.orders.runOrders) orders. Use a new RUN_ID." }
    if ($snapshot.totalRedisStock -ne [long]$Parameters.preparedTestStock -or $snapshot.totalSpannerAvailable -ne [long]$Parameters.preparedTestStock) {
        throw "Prepared stock mismatch: expected $($Parameters.preparedTestStock), Redis=$($snapshot.totalRedisStock), Spanner=$($snapshot.totalSpannerAvailable)."
    }
    $baseline = [ordered]@{ runId = $RunId; testStartedAt = [DateTimeOffset]::UtcNow.ToString('o'); state = $snapshot }
    $baseline | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $ResultDirectory 'baseline.json')
    Write-Host "Baseline recorded: RedisStock=$($snapshot.totalRedisStock) SpannerAvailable=$($snapshot.totalSpannerAvailable) Orders=$($snapshot.orders.totalOrders) RunOrders=$($snapshot.orders.runOrders) KafkaLag=$($snapshot.kafka.totalLag)"
    exit 0
}

$BaselinePath = Join-Path $ResultDirectory 'baseline.json'
$K6ResultPath = Join-Path $ResultDirectory 'k6-result.json'
if (-not (Test-Path $BaselinePath)) { throw 'Run the before phase before load testing; baseline.json is missing.' }
if (-not (Test-Path $K6ResultPath)) { throw 'k6-result.json is missing; the k6 run did not produce its machine-readable result.' }
$Baseline = Get-Content -Raw $BaselinePath | ConvertFrom-Json
$K6 = Get-Content -Raw $K6ResultPath | ConvertFrom-Json
$reserved = [long]$K6.businessResults.reserved
$verificationStarted = [DateTimeOffset]::UtcNow
$loadFinished = [DateTimeOffset]::Parse([string]$K6.generatedAt)
$deadline = $verificationStarted.AddSeconds($DrainTimeoutSeconds)
$drained = $false
do {
    $final = Get-Snapshot
    $redisLag = [long](($final.redisStreams | Measure-Object lag -Sum).Sum)
    $redisPending = [long](($final.redisStreams | Measure-Object pending -Sum).Sum)
    $newOrders = [long]$final.orders.runOrders - [long]$Baseline.state.orders.runOrders
    Write-Host "Drain: RedisLag=$redisLag RedisPending=$redisPending KafkaLag=$($final.kafka.totalLag) NewOrders=$newOrders/$reserved"
    $drained = $redisLag -eq 0 -and $redisPending -eq 0 -and $final.kafka.totalLag -eq 0 -and $newOrders -eq $reserved
    if (-not $drained -and [DateTimeOffset]::UtcNow -lt $deadline) { Start-Sleep -Seconds $PollIntervalSeconds }
} while (-not $drained -and [DateTimeOffset]::UtcNow -lt $deadline)

$finishedAt = [DateTimeOffset]::UtcNow
$newOrders = [long]$final.orders.runOrders - [long]$Baseline.state.orders.runOrders
$attempts = [long]$K6.throughput.attempts
$expectedReserved = [Math]::Min([long]$Parameters.preparedTestStock, $attempts)
$oversold = $final.totalRedisStock -lt 0 -or $final.totalSpannerAvailable -lt 0 -or $reserved -gt [long]$Parameters.preparedTestStock
$limitedExhausted = $Parameters.testProfile -ne 'limited_stock' -or $attempts -lt [long]$Parameters.preparedTestStock -or ($reserved -eq [long]$Parameters.preparedTestStock -and $final.totalRedisStock -eq 0)
$checks = [ordered]@{
    droppedIterationsZero = [long]$K6.throughput.droppedIterations -eq 0
    httpTechnicalErrorBelowPointOnePercent = [double]$K6.httpTechnicalErrorRate -lt 0.001
    duplicateZero = [long]$K6.businessResults.duplicate -eq 0
    failedZero = [long]$K6.businessResults.failed -eq 0
    parseUnknownContractErrorsZero = ([long]$K6.businessResults.parseError + [long]$K6.businessResults.unknown + [long]$K6.businessResults.contractError) -eq 0
    expectedReserved = $reserved -eq $expectedReserved
    limitedStockExhaustedWhenEnoughAttempts = $limitedExhausted
    noOversell = -not $oversold
    asyncOrdersConverged = $newOrders -eq $reserved
    backlogDrained = $drained
}
$passed = -not ($checks.Values -contains $false)
$verification = [ordered]@{
    runId = $RunId
    testStartedAt = $Baseline.testStartedAt
    verificationFinishedAt = $finishedAt.ToString('o')
    loadFinishedAt = $loadFinished.ToString('o')
    verificationStartedAt = $verificationStarted.ToString('o')
    drainTimeSeconds = [Math]::Round(($finishedAt - $loadFinished).TotalSeconds, 3)
    drainedWithinTimeout = $drained
    initialRedisStock = [long]$Baseline.state.totalRedisStock
    finalRedisStock = [long]$final.totalRedisStock
    initialSpannerAvailable = [long]$Baseline.state.totalSpannerAvailable
    finalSpannerAvailable = [long]$final.totalSpannerAvailable
    oversold = $oversold
    reserved = $reserved
    expectedReserved = $expectedReserved
    newOrders = $newOrders
    finalState = $final
    k6 = $K6
    checks = $checks
    passed = $passed
}
$verification | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 (Join-Path $ResultDirectory 'verification.json')

$report = @"
# Inventory load test result: $RunId

- Result: $(if ($passed) { 'PASS' } else { 'FAIL' })
- Profile / mode: $($Parameters.testProfile) / $($Parameters.skuMode)
- Target / actual sent / actual completed QPS: $($K6.throughput.targetQps) / $([Math]::Round($K6.throughput.actualSentQps, 2)) / $([Math]::Round($K6.throughput.actualCompletedQps, 2))
- RESERVED QPS: $([Math]::Round($K6.throughput.reservedQps, 2))
- RESERVED / SOLD_OUT / DUPLICATE / FAILED: $reserved / $($K6.businessResults.soldOut) / $($K6.businessResults.duplicate) / $($K6.businessResults.failed)
- Initial / final Redis stock: $($Baseline.state.totalRedisStock) / $($final.totalRedisStock)
- Initial / final Spanner available: $($Baseline.state.totalSpannerAvailable) / $($final.totalSpannerAvailable)
- Oversold: $oversold
- New orders / RESERVED: $newOrders / $reserved
- Backlog drained: $drained
- Verification drain wait: $($verification.drainTimeSeconds) seconds
- Dropped iterations: $($K6.throughput.droppedIterations)
- HTTP technical error rate: $([Math]::Round(100 * $K6.httpTechnicalErrorRate, 4))%
- Overall p50/p95/p99: $($K6.latency.overall.p50Ms) / $($K6.latency.overall.p95Ms) / $($K6.latency.overall.p99Ms) ms
- RESERVED p50/p95/p99: $($K6.latency.reserved.p50Ms) / $($K6.latency.reserved.p95Ms) / $($K6.latency.reserved.p99Ms) ms
- SOLD_OUT p50/p95/p99: $($K6.latency.soldOut.p50Ms) / $($K6.latency.soldOut.p95Ms) / $($K6.latency.soldOut.p99Ms) ms
"@
$report | Set-Content -Encoding utf8 (Join-Path $ResultDirectory 'report.md')
Write-Host "Verification $(if ($passed) { 'PASSED' } else { 'FAILED' }): $ResultDirectory/report.md"
if (-not $passed) { exit 1 }

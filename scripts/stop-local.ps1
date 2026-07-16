$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$PidDir = Join-Path $Root '.local/pids'

function Get-ListeningProcessIds {
    param([int]$Port)

    $processIds = @()
    try {
        $processIds += @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess)
    } catch {
        # netstat below is also used, so an unavailable/restricted cmdlet is OK.
    }
    # Always merge netstat results. On some Windows/Docker configurations the
    # cmdlet can return an incomplete view even without throwing.
    $pattern = '^\s*TCP\s+\S+:' + $Port + '\s+\S+\s+LISTENING\s+(\d+)\s*$'
    foreach ($line in (& netstat.exe -ano -p TCP)) {
        if ($line -match $pattern) {
            $processIds += [int]$Matches[1]
        }
    }
    return @($processIds | Where-Object { $_ -gt 0 } | Sort-Object -Unique)
}

function Stop-TargetProcess {
    param(
        [int]$ProcessId,
        [string]$Description
    )

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "$Description PID $ProcessId is already stopped."
        return
    }

    Write-Host "Stopping $Description PID $ProcessId ($($process.ProcessName))..."
    try {
        Stop-Process -Id $ProcessId -Force -ErrorAction Stop
    } catch {
        throw "Failed to stop $Description PID ${ProcessId}: $($_.Exception.Message)"
    }
}

# The PID returned by Start-Process(mvn.cmd) is only a wrapper process. It may
# have exited and its numeric PID may already belong to an unrelated process,
# so stale PID files are never used as a kill authority. Actual listeners below
# are the sole source of truth.
if (Test-Path $PidDir) {
    Get-ChildItem $PidDir -Filter '*.pid' | ForEach-Object {
        Write-Host "Discarding advisory PID file $($_.Name); listener ports determine the real service PIDs."
    }
}

# Stop the actual Java children by their known backend ports. Refuse to kill an
# unrelated non-Java listener so a port collision is visible and safe.
$ServicePorts = [ordered]@{
    'catalog-service'   = 8080
    'search-service'    = 8081
    'inventory-service' = 8082
    'order-service'     = 8083
    'payment-service'   = 8084
}

foreach ($entry in $ServicePorts.GetEnumerator()) {
    foreach ($processId in @(Get-ListeningProcessIds -Port $entry.Value)) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        if ($process.ProcessName -notin @('java', 'javaw')) {
            throw "$($entry.Key) port $($entry.Value) is owned by non-Java process PID $processId ($($process.ProcessName)); refusing to stop it."
        }
        Stop-TargetProcess -ProcessId $processId -Description "$($entry.Key) listener on port $($entry.Value)"
    }
}

$deadline = (Get-Date).AddSeconds(10)
do {
    $remaining = @()
    foreach ($entry in $ServicePorts.GetEnumerator()) {
        foreach ($processId in @(Get-ListeningProcessIds -Port $entry.Value)) {
            $remaining += "$($entry.Value)/PID:$processId"
        }
    }
    if ($remaining.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $deadline)

if ($remaining.Count -gt 0) {
    throw "Backend ports did not stop within 10 seconds: $($remaining -join ', ')"
}

if (Test-Path $PidDir) {
    Get-ChildItem $PidDir -Filter '*.pid' | Remove-Item -Force
}

Write-Host 'All backend ports 8080-8084 are stopped.'
& docker compose --env-file (Join-Path $Root 'infra/local/.env') -f (Join-Path $Root 'infra/local/docker-compose.yml') down
if ($LASTEXITCODE -ne 0) { throw 'docker compose down failed.' }
Write-Host 'Local dependency containers are stopped. Named volumes were preserved.'

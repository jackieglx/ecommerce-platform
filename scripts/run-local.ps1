param(
    [ValidateSet('all', 'deps-only', 'services-only')]
    [string]$Mode = 'all'
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeArgs = @('--env-file', (Join-Path $Root 'infra/local/.env'), '-f', (Join-Path $Root 'infra/local/docker-compose.yml'))
$LogDir = Join-Path $Root '.local/logs'
$PidDir = Join-Path $Root '.local/pids'

function Invoke-Compose { param([string[]]$Arguments) & docker compose @ComposeArgs @Arguments; if ($LASTEXITCODE -ne 0) { throw 'docker compose failed' } }
function Wait-Port { param([int]$Port, [string]$Name)
    $deadline = (Get-Date).AddSeconds(120)
    do {
        if (Test-NetConnection -ComputerName localhost -Port $Port -InformationLevel Quiet) { return }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Name"
}
function Wait-Health { param([int]$Port, [string]$Name)
    $deadline = (Get-Date).AddSeconds(120)
    $consecutive = 0
    do {
        try {
            $health = Invoke-RestMethod "http://localhost:$Port/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') { $consecutive++ } else { $consecutive = 0 }
            if ($consecutive -ge 3) { return }
        } catch { $consecutive = 0 }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for three consecutive healthy responses from $Name"
}

function Get-KafkaContainerState {
    $raw = & docker inspect --format '{{.State.Status}}|{{.RestartCount}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.LogPath}}' kafka 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $raw) { return $null }
    $parts = $raw -split '\|', 4
    return [pscustomobject]@{ Status = $parts[0]; RestartCount = [int]$parts[1]; Health = $parts[2]; LogPath = $parts[3] }
}

function Show-KafkaDiagnostics {
    $state = Get-KafkaContainerState
    if ($state) {
        Write-Host "Kafka container: status=$($state.Status), health=$($state.Health), restartCount=$($state.RestartCount)" -ForegroundColor Red
        Write-Host "Docker log path: $($state.LogPath)" -ForegroundColor Red
    }
    Write-Host "Kafka log command: docker compose --env-file infra/local/.env -f infra/local/docker-compose.yml logs kafka --tail 150" -ForegroundColor Yellow
    & docker compose @ComposeArgs logs kafka --tail 150
}

function Wait-KafkaReady {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $state = Get-KafkaContainerState
        if ($state -and ($state.Status -in @('restarting', 'exited', 'dead') -or $state.RestartCount -gt 0)) {
            Show-KafkaDiagnostics
            throw "Kafka container crashed before becoming ready (status=$($state.Status), restartCount=$($state.RestartCount))."
        }
        if ($state -and $state.Status -eq 'running' -and $state.Health -eq 'healthy' -and
            (Test-NetConnection -ComputerName localhost -Port 29092 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    Show-KafkaDiagnostics
    throw 'Timed out waiting for Kafka health and localhost:29092 after 120 seconds.'
}

function Initialize-KafkaTopics { param([string]$BashPath)
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        & $BashPath (Join-Path $Root 'infra/local/kafka/topics.sh')
        if ($LASTEXITCODE -eq 0) { return }
        $state = Get-KafkaContainerState
        if ($state -and ($state.Status -in @('restarting', 'exited', 'dead') -or $state.RestartCount -gt 0)) { break }
        Write-Host "Kafka topic initialization attempt $attempt/5 failed; retrying in 3 seconds..." -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    }
    Show-KafkaDiagnostics
    throw 'Kafka topic initialization failed after 5 attempts. See the container state and Kafka logs above.'
}

if ($Mode -ne 'services-only') {
    Invoke-Compose @('up', '-d', 'spanner', 'kafka', 'redis', 'elasticsearch')
    Wait-Port 9010 'Spanner'; Wait-KafkaReady; Wait-Port 6379 'Redis'; Wait-Port 9200 'Elasticsearch'
    Invoke-Compose @('run', '--rm', '--entrypoint', 'bash', 'spanner-tools', 'spanner/bootstrap.sh')
    # Prefer Git Bash. Windows' built-in bash.exe is a WSL launcher and may exist
    # even when no Linux distribution is installed.
    $gitBash = 'C:\Program Files\Git\bin\bash.exe'
    $bash = if (Test-Path $gitBash) { $gitBash } else { (Get-Command bash -ErrorAction SilentlyContinue).Source }
    if (-not $bash) { throw 'bash is required to initialize Kafka topics; install Git for Windows or make bash available on PATH.' }
    Initialize-KafkaTopics $bash
}
if ($Mode -eq 'deps-only') { Write-Host 'Dependencies are ready. Run scripts/run-local.ps1 -Mode services-only to start Java services.'; exit 0 }

$env:SPANNER_PROJECT = 'local-project'; $env:SPANNER_INSTANCE = 'local-instance'; $env:SPANNER_DATABASE = 'local-db'
$env:SPANNER_EMULATOR_HOST = 'localhost:9010'; $env:SPANNER_EMULATOR_ENABLED = 'true'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:29092'; $env:REDIS_HOST = 'localhost'; $env:REDIS_PORT = '6379'
$env:ELASTICSEARCH_URL = 'http://localhost:9200'; $env:CATALOG_BASE_URL = 'http://localhost:8080'; $env:SPRING_PROFILES_ACTIVE = 'local'
$env:SPRING_AUTOCONFIGURE_EXCLUDE = 'com.google.cloud.spring.autoconfigure.spanner.GcpSpannerAutoConfiguration,com.google.cloud.spring.autoconfigure.spanner.SpannerTransactionManagerAutoConfiguration'
New-Item -ItemType Directory -Force -Path $LogDir, $PidDir | Out-Null
& mvn.cmd -q -DskipTests install
if ($LASTEXITCODE -ne 0) { throw 'Maven install failed' }

function Start-ServiceLocal { param([string]$Module, [string]$Name, [int]$Port)
    $pidFile = Join-Path $PidDir "$Name.pid"
    if (Test-Path $pidFile) {
        $existing = Get-Process -Id (Get-Content $pidFile) -ErrorAction SilentlyContinue
        if ($existing) {
            try {
                $health = Invoke-RestMethod "http://localhost:$Port/actuator/health" -TimeoutSec 3
                if ($health.status -eq 'UP') { Write-Host "$Name is already running and healthy"; return }
            } catch { }
            Write-Host "$Name has a live PID but no healthy endpoint; restarting it." -ForegroundColor Yellow
            Stop-Process -Id $existing.Id -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        }
    }
    $log = Join-Path $LogDir "$Name-$((Get-Date).ToString('yyyyMMddHHmmss')).log"
    $process = Start-Process -FilePath 'mvn.cmd' -ArgumentList @('-q', '-pl', $Module, 'org.springframework.boot:spring-boot-maven-plugin:3.5.9:run', '-Dspring-boot.run.profiles=local') -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $process.Id
    try { Wait-Health $Port $Name } catch {
        Write-Host "$Name startup log: $log" -ForegroundColor Red
        Get-Content -Tail 100 -Path $log, "$log.err" -ErrorAction SilentlyContinue
        throw
    }
}

Start-ServiceLocal 'services/catalog-service' 'catalog-service' 8080
Start-ServiceLocal 'services/search-service' 'search-service' 8081
Start-ServiceLocal 'services/inventory-service' 'inventory-service' 8082
Start-ServiceLocal 'services/order-service' 'order-service' 8083
Start-ServiceLocal 'services/payment-service' 'payment-service' 8084
Write-Host 'Core services are ready. Run scripts/smoke-local.ps1 for the minimal transaction smoke test.'

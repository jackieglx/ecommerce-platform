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
    do {
        try { Invoke-RestMethod "http://localhost:$Port/actuator/health" -TimeoutSec 3 | Out-Null; return } catch { Start-Sleep -Seconds 1 }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Name health endpoint"
}

if ($Mode -ne 'services-only') {
    Invoke-Compose @('up', '-d', 'spanner', 'zookeeper', 'kafka', 'redis', 'elasticsearch')
    Wait-Port 9010 'Spanner'; Wait-Port 29092 'Kafka'; Wait-Port 6379 'Redis'; Wait-Port 9200 'Elasticsearch'
    Invoke-Compose @('run', '--rm', '--entrypoint', 'bash', 'spanner-tools', 'spanner/bootstrap.sh')
    # Prefer Git Bash. Windows' built-in bash.exe is a WSL launcher and may exist
    # even when no Linux distribution is installed.
    $gitBash = 'C:\Program Files\Git\bin\bash.exe'
    $bash = if (Test-Path $gitBash) { $gitBash } else { (Get-Command bash -ErrorAction SilentlyContinue).Source }
    if (-not $bash) { throw 'bash is required to initialize Kafka topics; install Git for Windows or make bash available on PATH.' }
    & $bash (Join-Path $Root 'infra/local/kafka/topics.sh')
    if ($LASTEXITCODE -ne 0) { throw 'Kafka topic initialization failed' }
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
        if ($existing) { Write-Host "$Name is already running"; return }
    }
    $log = Join-Path $LogDir "$Name-$((Get-Date).ToString('yyyyMMddHHmmss')).log"
    $process = Start-Process -FilePath 'mvn.cmd' -ArgumentList @('-q', '-pl', $Module, 'org.springframework.boot:spring-boot-maven-plugin:3.5.9:run', '-Dspring-boot.run.profiles=local') -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    Set-Content -Path $pidFile -Value $process.Id
    Wait-Health $Port $Name
}

Start-ServiceLocal 'services/catalog-service' 'catalog-service' 8080
Start-ServiceLocal 'services/search-service' 'search-service' 8081
Start-ServiceLocal 'services/inventory-service' 'inventory-service' 8082
Start-ServiceLocal 'services/order-service' 'order-service' 8083
Start-ServiceLocal 'services/payment-service' 'payment-service' 8084
Write-Host 'Core services are ready. Run scripts/smoke-local.ps1 for the minimal transaction smoke test.'

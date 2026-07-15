$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$PidDir = Join-Path $Root '.local/pids'
if (Test-Path $PidDir) {
    Get-ChildItem $PidDir -Filter '*.pid' | ForEach-Object {
        & taskkill.exe /PID (Get-Content $_.FullName) /T /F 2>$null | Out-Null
        Remove-Item $_.FullName -Force
    }
}
& docker compose --env-file (Join-Path $Root 'infra/local/.env') -f (Join-Path $Root 'infra/local/docker-compose.yml') down

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeArgs = @('--env-file', (Join-Path $Root 'infra/local/.env'), '-f', (Join-Path $Root 'infra/local/docker-compose.yml'))
$skuId = "smoke-sku-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$userId = 'smoke-user'
function Invoke-Compose { param([string[]]$Arguments) & docker compose @ComposeArgs @Arguments; if ($LASTEXITCODE -ne 0) { throw 'docker compose failed' } }
function Wait-OrderStatus { param([string]$OrderId, [string]$Status)
    $deadline = (Get-Date).AddSeconds(45)
    do {
        try {
            $order = Invoke-RestMethod "http://localhost:8083/api/v1/orders/$OrderId" -TimeoutSec 5
            if ($order.status -eq $Status) { return }
        } catch {
            # The reservation event can arrive before Order Service has persisted the order.
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Order $OrderId did not reach $Status"
}

8080..8084 | ForEach-Object { Invoke-RestMethod "http://localhost:$_/actuator/health" -TimeoutSec 5 | Out-Null }
Invoke-RestMethod http://localhost:8080/internal/admin/v1/skus -Method Post -ContentType 'application/json' -Body (@{skuId=$skuId;productId='smoke-product';title='Smoke SKU';status='ACTIVE';brand='local';priceCents=1999;currency='USD'} | ConvertTo-Json) | Out-Null
Invoke-RestMethod http://localhost:8082/internal/inventory/seed -Method Post -ContentType 'application/json' -Body (@{skuId=$skuId;onHand=5} | ConvertTo-Json) | Out-Null
$reservation = Invoke-RestMethod http://localhost:8082/api/v1/flashsale/reservations -Method Post -Headers @{'X-User-Id'=$userId;'Idempotency-Key'="smoke-$([guid]::NewGuid())"} -ContentType 'application/json' -Body (@{skuId=$skuId;qty=1} | ConvertTo-Json)
if ($reservation.status -ne 'RESERVED' -or -not $reservation.orderId) { throw "Reservation failed: $($reservation | ConvertTo-Json -Compress)" }
Wait-OrderStatus $reservation.orderId 'PENDING_PAYMENT'
Invoke-RestMethod http://localhost:8084/api/v1/payments/succeed -Method Post -Headers @{'X-User-Id'=$userId} -ContentType 'application/json' -Body (@{orderId=$reservation.orderId} | ConvertTo-Json) | Out-Null
Wait-OrderStatus $reservation.orderId 'PAID'
Write-Host "Smoke test passed: SKU=$skuId order=$($reservation.orderId) status=PAID"

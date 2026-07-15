$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Manifest = Join-Path $PSScriptRoot 'demo-products.tsv'
$CatalogBaseUrl = 'http://localhost:8080'
$InventoryBaseUrl = 'http://localhost:8082'

function Assert-Healthy {
    param([string]$Name, [string]$Url)
    $health = Invoke-RestMethod "$Url/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw "$Name is not healthy: $($health.status)" }
}

function Get-HttpStatus {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)
    if ($null -ne $ErrorRecord.Exception.Response) {
        return [int]$ErrorRecord.Exception.Response.StatusCode
    }
    return 0
}

function ConvertTo-JsonBody {
    param([object]$Value)
    $json = $Value | ConvertTo-Json -Compress
    # Windows PowerShell 5.1 can encode string request bodies inconsistently.
    # JSON Unicode escapes keep the HTTP body ASCII while preserving real text.
    return [System.Text.RegularExpressions.Regex]::Replace(
        $json,
        '[^\u0000-\u007F]',
        { param($match) ('\u{0:x4}' -f [int][char]$match.Value[0]) }
    )
}

Assert-Healthy 'Catalog Service' $CatalogBaseUrl
Assert-Healthy 'Inventory Service' $InventoryBaseUrl

$products = Import-Csv -Path $Manifest -Delimiter "`t" -Encoding utf8
foreach ($product in $products) {
    $catalogPayload = @{
        title      = $product.title
        status     = $product.status
        brand      = $product.brand
        priceCents = [long]$product.priceCents
        currency   = $product.currency
    }

    try {
        Invoke-RestMethod "$CatalogBaseUrl/api/v1/skus/$($product.skuId)" -TimeoutSec 5 | Out-Null
        Invoke-RestMethod "$CatalogBaseUrl/internal/admin/v1/skus/$($product.skuId)" -Method Patch -ContentType 'application/json' -Body (ConvertTo-JsonBody $catalogPayload) | Out-Null
        Write-Host "Updated Catalog SKU $($product.skuId)"
    } catch {
        if ((Get-HttpStatus $_) -ne 404) { throw }
        $createPayload = $catalogPayload + @{
            skuId     = $product.skuId
            productId = $product.productId
        }
        Invoke-RestMethod "$CatalogBaseUrl/internal/admin/v1/skus" -Method Post -ContentType 'application/json' -Body (ConvertTo-JsonBody $createPayload) | Out-Null
        Write-Host "Created Catalog SKU $($product.skuId)"
    }

    $inventoryPayload = @{ skuId = $product.skuId; onHand = [long]$product.inventory } | ConvertTo-Json -Compress
    Invoke-RestMethod "$InventoryBaseUrl/internal/inventory/seed" -Method Post -ContentType 'application/json' -Body $inventoryPayload | Out-Null
    Write-Host "Seeded Inventory SKU $($product.skuId) onHand=$($product.inventory)"
}

$ids = @($products | ForEach-Object { $_.skuId })
$catalogSkus = Invoke-RestMethod "$CatalogBaseUrl/api/v1/skus/batch" -Method Post -ContentType 'application/json' -Body ($ids | ConvertTo-Json -Compress)
if ($catalogSkus.Count -ne $products.Count) {
    throw "Catalog verification failed: expected $($products.Count) demo SKUs, got $($catalogSkus.Count)"
}

Write-Host "Demo data ready: $($products.Count) Catalog SKUs with Inventory stock."

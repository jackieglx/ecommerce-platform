# 01-create-index.ps1
$ErrorActionPreference = "Stop"

# 当前脚本所在目录：.../infra/local/elasticsearch/init
$ROOT = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path   # .../infra/local/elasticsearch
$LOCAL = (Resolve-Path (Join-Path $ROOT "..")).Path          # .../infra/local

# 读取 .env（如果没有就读 env.example）
$envFile = $env:ENV_FILE
if ([string]::IsNullOrWhiteSpace($envFile)) {
  $envFile = Join-Path $LOCAL ".env"
}
if (!(Test-Path $envFile)) {
  $fallback = Join-Path $LOCAL "env.example"
  if (Test-Path $fallback) { $envFile = $fallback }
}

if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $k, $v = $line.Split("=", 2)
      $k = $k.Trim()
      $v = $v.Trim().Trim('"')
      if ($k) { Set-Item -Path "Env:$k" -Value $v }
    }
  }
}

$ES_URL = $env:ELASTICSEARCH_URL
if ([string]::IsNullOrWhiteSpace($ES_URL)) { $ES_URL = "http://localhost:9200" }

Write-Host "Applying products_v1 index template to $ES_URL"

$templatePath = Join-Path $ROOT "index-templates\products_v1.json"
$templateBody = Get-Content $templatePath -Raw

Invoke-RestMethod -Method Put `
  -Uri "$ES_URL/_index_template/products_v1" `
  -ContentType "application/json" `
  -Body $templateBody | Out-Null

Write-Host "Ensuring products_v1 index exists"

try {
  Invoke-RestMethod -Method Get -Uri "$ES_URL/products_v1" | Out-Null
  Write-Host "products_v1 already exists"
} catch {
  $mappingPath = Join-Path $ROOT "mappings\products_v1.json"
  $mappingBody = Get-Content $mappingPath -Raw

  Invoke-RestMethod -Method Put `
    -Uri "$ES_URL/products_v1" `
    -ContentType "application/json" `
    -Body $mappingBody | Out-Null

  Write-Host "products_v1 created"
}

Write-Host "Done."

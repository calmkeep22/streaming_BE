param(
    [string]$ComposeFile = "docker-compose.sentinel.yml",
    [int]$TimeoutSeconds = 180,
    [switch]$RunFailover
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $projectRoot $ComposeFile
$prometheusBaseUrl = "http://localhost:9090"
$grafanaBaseUrl = "http://localhost:3000"
$alertmanagerBaseUrl = "http://localhost:9093"

function Wait-HttpOk {
    param([string]$Url)

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw "Timed out waiting for $Url"
}

function Invoke-PrometheusQuery {
    param([string]$Query)

    $encodedQuery = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod -Uri "$prometheusBaseUrl/api/v1/query?query=$encodedQuery"
    if ($response.status -ne "success") {
        throw "Prometheus query failed: $Query"
    }
    return $response.data.result
}

Write-Host "Starting application and monitoring stack..."
& docker compose -f $composePath up -d
if ($LASTEXITCODE -ne 0) {
    throw "docker compose up failed"
}

Wait-HttpOk "$prometheusBaseUrl/-/ready"
Wait-HttpOk "$grafanaBaseUrl/api/health"
Wait-HttpOk "$alertmanagerBaseUrl/-/ready"

$targetsResponse = Invoke-RestMethod -Uri "$prometheusBaseUrl/api/v1/targets"
$expectedJobs = @("streaming-app", "redis-data", "redis-sentinel")
$activeTargets = @($targetsResponse.data.activeTargets | Where-Object { $_.labels.job -in $expectedJobs })
$unhealthyTargets = @($activeTargets | Where-Object { $_.health -ne "up" })

if ($activeTargets.Count -ne 6) {
    throw "Expected 6 scrape targets but found $($activeTargets.Count)."
}
if ($unhealthyTargets.Count -gt 0) {
    $details = $unhealthyTargets | ForEach-Object { "$($_.labels.job):$($_.scrapeUrl)=$($_.health)" }
    throw "Unhealthy Prometheus targets: $($details -join ', ')"
}

$upByJob = Invoke-PrometheusQuery 'sum by (job) (up{job=~"streaming-app|redis-data|redis-sentinel"})'
$upByJob | ForEach-Object {
    Write-Host ("UP job={0} targets={1}" -f $_.metric.job, $_.value[1])
}

$redisScrapeErrors = Invoke-PrometheusQuery 'sum(redis_exporter_last_scrape_error{job=~"redis-data|redis-sentinel"})'
if ($redisScrapeErrors.Count -eq 0 -or [double]$redisScrapeErrors[0].value[1] -ne 0) {
    throw "At least one Redis exporter cannot scrape its target."
}

if ($RunFailover) {
    Write-Host "Running Sentinel failover scenario..."
    & (Join-Path $PSScriptRoot "redis-sentinel-failover.ps1") -ComposeFile $ComposeFile -SkipStartup
    if ($LASTEXITCODE -ne 0) {
        throw "Sentinel failover scenario failed"
    }

    Start-Sleep -Seconds 10
    $sentinelUp = Invoke-PrometheusQuery 'sum(up{job="redis-sentinel"})'
    if ($sentinelUp.Count -eq 0 -or [double]$sentinelUp[0].value[1] -lt 2) {
        throw "Sentinel quorum was not restored after failover."
    }
}

Write-Host "Monitoring smoke test passed."
Write-Host "Grafana:      $grafanaBaseUrl"
Write-Host "Prometheus:   $prometheusBaseUrl"
Write-Host "Alertmanager: $alertmanagerBaseUrl"

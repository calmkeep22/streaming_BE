param(
    [string]$ComposeFile = "docker-compose.sentinel.yml",
    [int]$TimeoutSeconds = 30,
    [switch]$SkipStartup
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $projectRoot $ComposeFile
$redisPassword = if ($env:REDIS_PASSWORD) { $env:REDIS_PASSWORD } else { "streaming-redis-dev" }
$sentinelPassword = if ($env:REDIS_SENTINEL_PASSWORD) { $env:REDIS_SENTINEL_PASSWORD } else { "streaming-sentinel-dev" }
$masterName = "streaming-master"
$primaryAddress = "172.29.0.10"
$replicaAddress = "172.29.0.11"
$probeKey = "sentinel:failover:probe"
$probeValue = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
$stoppedService = $null
$promotionSucceeded = $false

function Invoke-Compose {
    param([string[]]$ComposeArgs)

    & docker compose -f $composePath @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($ComposeArgs -join ' ')"
    }
}

function Get-SentinelMaster {
    $lines = & docker compose -f $composePath exec -T redis-sentinel-1 `
        redis-cli --raw --no-auth-warning -a $sentinelPassword -p 26379 `
        SENTINEL get-master-addr-by-name $masterName

    if ($LASTEXITCODE -ne 0 -or $lines.Count -lt 2) {
        return $null
    }

    return [PSCustomObject]@{
        Host = $lines[0].Trim()
        Port = [int]$lines[1].Trim()
    }
}

function Get-RedisRole {
    param([string]$Service)

    $role = & docker compose -f $composePath exec -T $Service `
        redis-cli --raw --no-auth-warning -a $redisPassword ROLE | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or -not $role) {
        return $null
    }
    return $role.Trim()
}

function Wait-Until {
    param(
        [scriptblock]$Condition,
        [string]$FailureMessage
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $result = & $Condition
        if ($result) {
            return $result
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw $FailureMessage
}

try {
    if (-not $SkipStartup) {
        Invoke-Compose -ComposeArgs @(
            "up", "-d",
            "redis-primary", "redis-replica",
            "redis-sentinel-1", "redis-sentinel-2", "redis-sentinel-3"
        )
    }

    $initialMaster = Wait-Until `
        { Get-SentinelMaster } `
        "Sentinel did not report a master."

    if ($initialMaster.Host -eq $primaryAddress) {
        $currentMasterService = "redis-primary"
        $promotionTargetService = "redis-replica"
        $promotionTargetAddress = $replicaAddress
    }
    elseif ($initialMaster.Host -eq $replicaAddress) {
        $currentMasterService = "redis-replica"
        $promotionTargetService = "redis-primary"
        $promotionTargetAddress = $primaryAddress
    }
    else {
        throw "Sentinel reported an unknown master address: $($initialMaster.Host)"
    }

    $reportedMasterRole = Get-RedisRole -Service $currentMasterService
    if ($reportedMasterRole -ne "master") {
        throw "Sentinel/Redis role mismatch: Sentinel reports $currentMasterService, ROLE=$reportedMasterRole"
    }

    Invoke-Compose -ComposeArgs @(
        "exec", "-T", $currentMasterService, "redis-cli", "--no-auth-warning",
        "-a", $redisPassword, "SET", $probeKey, $probeValue
    )

    Wait-Until `
        {
            $value = & docker compose -f $composePath exec -T $promotionTargetService `
                redis-cli --raw --no-auth-warning -a $redisPassword GET $probeKey
            if ($LASTEXITCODE -eq 0 -and $value -eq $probeValue) { $true }
        } `
        "Probe key was not replicated before failover."

    $failoverStartedAt = [DateTimeOffset]::UtcNow
    Invoke-Compose -ComposeArgs @("stop", $currentMasterService)
    $stoppedService = $currentMasterService

    $promotedMaster = Wait-Until `
        { $master = Get-SentinelMaster; if ($master -and $master.Host -eq $promotionTargetAddress) { $master } } `
        "Sentinel did not promote $promotionTargetService within $TimeoutSeconds seconds."

    $failoverMilliseconds = [int]([DateTimeOffset]::UtcNow - $failoverStartedAt).TotalMilliseconds

    $role = & docker compose -f $composePath exec -T $promotionTargetService `
        redis-cli --raw --no-auth-warning -a $redisPassword ROLE | Select-Object -First 1
    $replicatedValue = & docker compose -f $composePath exec -T $promotionTargetService `
        redis-cli --raw --no-auth-warning -a $redisPassword GET $probeKey

    if ($role.Trim() -ne "master") {
        throw "$promotionTargetService was not promoted. ROLE=$role"
    }
    if ($replicatedValue.Trim() -ne $probeValue) {
        throw "The replicated probe key was lost during failover."
    }
    $promotionSucceeded = $true

    [PSCustomObject]@{
        InitialMaster = "$($initialMaster.Host):$($initialMaster.Port)"
        PromotedMaster = "$($promotedMaster.Host):$($promotedMaster.Port)"
        FailoverMilliseconds = $failoverMilliseconds
        ProbeKeyPreserved = $true
        PromotedService = $promotionTargetService
        PromotedRoleAfterFailover = $role.Trim()
    } | ConvertTo-Json
}
finally {
    if ($stoppedService) {
        Invoke-Compose -ComposeArgs @("start", $stoppedService)
        if (-not $promotionSucceeded) {
            Invoke-Compose -ComposeArgs @(
                "exec", "-T", $currentMasterService, "redis-cli", "--no-auth-warning",
                "-a", $redisPassword, "REPLICAOF", "NO", "ONE"
            )
            Invoke-Compose -ComposeArgs @(
                "exec", "-T", $promotionTargetService, "redis-cli", "--no-auth-warning",
                "-a", $redisPassword, "REPLICAOF", $initialMaster.Host, $initialMaster.Port.ToString()
            )
        }
    }
}

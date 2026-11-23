param(
    [string]$ComposeFile = "docker-compose.sentinel.yml",
    [int]$TimeoutSeconds = 30,
    [ValidateSet("Kill", "Stop")]
    [string]$FailureMode = "Kill",
    [string]$ObservedKey = "",
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
$dockerExecutable = (Get-Command docker.exe -CommandType Application | Select-Object -First 1).Source
. (Join-Path $PSScriptRoot "sentinel-events.ps1")
$redisContainers = @{}

function Write-Diagnostic {
    param([hashtable]$Event)
    if ($env:LOADTEST_DIAGNOSTICS_PATH) {
        $Event.at = [DateTimeOffset]::UtcNow.ToString("O")
        $json = $Event | ConvertTo-Json -Compress -Depth 5
        [System.IO.File]::AppendAllText($env:LOADTEST_DIAGNOSTICS_PATH, $json + [Environment]::NewLine)
    }
}

# Keep native stdout/exit-code behavior while durably recording the start even if docker hangs.
function docker {
    $commandArgs = @($args)
    $safeArgs = @()
    $secretNext = $false
    foreach ($argument in $commandArgs) {
        if ($secretNext) { $safeArgs += "[REDACTED]"; $secretNext = $false }
        else {
            $safeArgs += $argument
            if ($argument -in @("-a", "--pass", "--password")) { $secretNext = $true }
        }
    }
    $id = [Guid]::NewGuid().ToString()
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Diagnostic @{ event = "command_start"; commandId = $id; command = "docker"; args = $safeArgs }
    try {
        & $dockerExecutable @commandArgs
    }
    finally {
        $code = $LASTEXITCODE
        Write-Diagnostic @{ event = "command_end"; commandId = $id; elapsedMs = $watch.Elapsed.TotalMilliseconds; exitCode = $code }
        $global:LASTEXITCODE = $code
    }
}

function Invoke-Compose {
    param([string[]]$ComposeArgs)

    & docker compose -f $composePath @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($ComposeArgs -join ' ')"
    }
}

function Get-SentinelMaster {
    $lines = @(& docker exec $redisContainers["redis-sentinel-1"] `
        redis-cli --raw --no-auth-warning -a $sentinelPassword -p 26379 `
        SENTINEL get-master-addr-by-name $masterName)

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

    $lines = @(& docker exec $redisContainers[$Service] `
        redis-cli --raw --no-auth-warning -a $redisPassword ROLE)
    $commandExitCode = $LASTEXITCODE
    if ($commandExitCode -ne 0 -or $lines.Count -eq 0 -or -not $lines[0]) {
        return $null
    }
    return $lines[0].Trim()
}

function Wait-SentinelPromotion {
    param([string]$TargetAddress)

    # Keep polling inside a single container exec. No password crosses the command line.
    # Avoid embedded quotes in the shell payload for Windows PowerShell native argument handling.
    $pollScript = @'
REDISCLI_AUTH=$REDIS_SENTINEL_PASSWORD
export REDISCLI_AUTH
while :; do
    master=$(redis-cli --raw -p 26379 SENTINEL get-master-addr-by-name streaming-master | head -n 1)
    if [ x$master = xTARGET_ADDRESS ]; then
        echo $master
        echo 6379
        exit 0
    fi
    sleep 0.25
done
'@
    $pollScript = $pollScript.Replace('TARGET_ADDRESS', $TargetAddress).Replace("`r", "")
    $lines = @(& docker exec $redisContainers["redis-sentinel-1"] timeout $TimeoutSeconds sh -c $pollScript)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -lt 2 -or $lines[0].Trim() -ne $TargetAddress) {
        throw "Sentinel did not promote $TargetAddress within $TimeoutSeconds seconds."
    }
    return [PSCustomObject]@{ Host = $lines[0].Trim(); Port = [int]$lines[1].Trim() }
}

function Get-RedisValue {
    param(
        [string]$Service,
        [string]$Key
    )

    if (-not $Key) {
        return $null
    }

    $value = & docker exec $redisContainers[$Service] `
        redis-cli --raw --no-auth-warning -a $redisPassword GET $Key
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    return "$value".Trim()
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

    # Resolve identities once; polling no longer reloads the Compose project each time.
    $containerLines = @(& docker compose -f $composePath ps --format "{{.Service}} {{.ID}}" redis-primary redis-replica redis-sentinel-1)
    if ($LASTEXITCODE -ne 0) { throw "Could not resolve Redis container IDs." }
    foreach ($line in $containerLines) {
        if (-not $line.Trim()) { continue }
        $fields = $line.Trim() -split '\s+'
        if ($fields.Count -ne 2) { throw "Unexpected container identity response." }
        $redisContainers[$fields[0]] = $fields[1]
    }
    foreach ($service in @("redis-primary", "redis-replica", "redis-sentinel-1")) {
        if (-not $redisContainers[$service]) { throw "Redis service is not running: $service" }
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

    $reportedMasterRole = Wait-Until `
        { $role = Get-RedisRole -Service $currentMasterService; if ($role -eq "master") { $role } } `
        "Sentinel/Redis role mismatch: Sentinel reports $currentMasterService but Redis did not report master."

    & docker exec $redisContainers[$currentMasterService] redis-cli --raw --no-auth-warning `
        -a $redisPassword SET $probeKey $probeValue
    if ($LASTEXITCODE -ne 0) { throw "Could not write the replication probe." }

    Wait-Until `
        {
            $value = & docker exec $redisContainers[$promotionTargetService] `
                redis-cli --raw --no-auth-warning -a $redisPassword GET $probeKey
            if ($LASTEXITCODE -eq 0 -and $value -eq $probeValue) { $true }
        } `
        "Probe key was not replicated before failover."

    $observedValueBefore = Get-RedisValue -Service $currentMasterService -Key $ObservedKey
    $failureCommandStartedAt = [DateTimeOffset]::UtcNow
    $failureCommand = if ($FailureMode -eq "Kill") { "kill" } else { "stop" }
    $stoppedService = $currentMasterService
    # Native docker kill/stop avoids the detached Compose process that can outlive promotion.
    & docker $failureCommand $redisContainers[$currentMasterService]
    if ($LASTEXITCODE -ne 0) { throw "docker $failureCommand failed for $currentMasterService." }

    Wait-Until `
        { $role = Get-RedisRole -Service $currentMasterService; if ($role -ne "master") { $true } } `
        "$currentMasterService did not become unavailable within $TimeoutSeconds seconds."
    $primaryUnavailableAt = [DateTimeOffset]::UtcNow

    $promotedMaster = Wait-SentinelPromotion -TargetAddress $promotionTargetAddress
    $promotionObservedAt = [DateTimeOffset]::UtcNow

    $role = Wait-Until `
        { $reportedRole = Get-RedisRole -Service $promotionTargetService; if ($reportedRole -eq "master") { $reportedRole } } `
        "$promotionTargetService did not report the master role."
    $promotedRoleConfirmedAt = [DateTimeOffset]::UtcNow

    # Fetch all three Sentinel logs once and reuse them for every event timestamp.
    $sentinelLogs = @(& docker compose -f $composePath logs --no-color --timestamps `
        --since $failureCommandStartedAt.ToString("O") redis-sentinel-1 redis-sentinel-2 redis-sentinel-3)
    if ($LASTEXITCODE -ne 0) { throw "Could not collect Sentinel event logs." }
    $events = Read-SentinelEvents -Lines $sentinelLogs -MasterName $masterName `
        -InitialHost $initialMaster.Host -PromotedHost $promotionTargetAddress
    $sentinelSubjectiveDownAt = $events.SubjectiveDown
    $sentinelObjectiveDownAt = $events.ObjectiveDown
    $promotionReportedAt = $events.Promotion
    if (-not $sentinelSubjectiveDownAt -or -not $sentinelObjectiveDownAt -or -not $promotionReportedAt) {
        throw "Sentinel SDOWN/ODOWN/switch-master event timestamps were not found in the Sentinel logs."
    }

    $replicatedValue = & docker exec $redisContainers[$promotionTargetService] `
        redis-cli --raw --no-auth-warning -a $redisPassword GET $probeKey

    if ($replicatedValue.Trim() -ne $probeValue) {
        throw "The replicated probe key was lost during failover."
    }
    $observedValueAfter = Get-RedisValue -Service $promotionTargetService -Key $ObservedKey
    $observedKeyPreserved = if ($ObservedKey -and $observedValueBefore) {
        $observedValueAfter -eq $observedValueBefore
    }
    else {
        $null
    }

    [PSCustomObject]@{
        InitialMaster = "$($initialMaster.Host):$($initialMaster.Port)"
        PromotedMaster = "$($promotedMaster.Host):$($promotedMaster.Port)"
        FailureMode = $FailureMode
        Timeline = [PSCustomObject]@{
            FailureCommandStartedAtUtc = $failureCommandStartedAt.ToString("O")
            PrimaryUnavailableAtUtc = $primaryUnavailableAt.ToString("O")
            SentinelSubjectiveDownAtUtc = $sentinelSubjectiveDownAt.ToString("O")
            SentinelObjectiveDownAtUtc = $sentinelObjectiveDownAt.ToString("O")
            PromotionReportedAtUtc = $promotionReportedAt.ToString("O")
            PromotionObservedAtUtc = $promotionObservedAt.ToString("O")
            PromotionTimestampSource = "sentinel_switch_master_log"
            PromotedRoleConfirmedAtUtc = $promotedRoleConfirmedAt.ToString("O")
        }
        DurationsMs = [PSCustomObject]@{
            PrimaryUnavailableDetectedAfterFailureCommand = [int]($primaryUnavailableAt - $failureCommandStartedAt).TotalMilliseconds
            SubjectiveDownAfterFailureCommand = [int]($sentinelSubjectiveDownAt - $failureCommandStartedAt).TotalMilliseconds
            ObjectiveDownAfterFailureCommand = [int]($sentinelObjectiveDownAt - $failureCommandStartedAt).TotalMilliseconds
            PromotionAfterFailureCommand = [int]($promotionReportedAt - $failureCommandStartedAt).TotalMilliseconds
            RoleConfirmationAfterFailureCommand = [int]($promotedRoleConfirmedAt - $failureCommandStartedAt).TotalMilliseconds
        }
        FailoverMilliseconds = [int]($promotionReportedAt - $failureCommandStartedAt).TotalMilliseconds
        ProbeKeyPreserved = $true
        ObservedKey = if ($ObservedKey) { $ObservedKey } else { $null }
        ObservedKeyPresentBefore = [bool]$observedValueBefore
        ObservedKeyPreserved = $observedKeyPreserved
        PromotedService = $promotionTargetService
        PromotedRoleAfterFailover = $role
    } | ConvertTo-Json
}
finally {
    Write-Diagnostic @{ event = "cleanup_start" }
    if ($stoppedService) {
        & docker start $redisContainers[$stoppedService]
        if ($LASTEXITCODE -ne 0) { throw "Could not restart $stoppedService." }
        # Sentinel owns topology. A metrics failure must never force a promoted master back to replica.
    }
    Write-Diagnostic @{ event = "cleanup_end" }
}

function Read-SentinelEvents {
    param(
        [string[]]$Lines,
        [string]$MasterName,
        [string]$InitialHost,
        [string]$PromotedHost
    )

    $events = @{ SubjectiveDown = $null; ObjectiveDown = $null; Promotion = $null }
    $namePattern = [regex]::Escape($MasterName)
    $initialPattern = [regex]::Escape($InitialHost)
    $promotedPattern = [regex]::Escape($PromotedHost)
    foreach ($line in $Lines) {
        $timestampMatch = [regex]::Match($line, '\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z')
        if (-not $timestampMatch.Success) { continue }
        $kind = $null
        if ($line -match "\+sdown master $namePattern $initialPattern 6379(?:\s|$)") { $kind = "SubjectiveDown" }
        elseif ($line -match "\+odown master $namePattern $initialPattern 6379(?:\s|$)") { $kind = "ObjectiveDown" }
        elseif ($line -match "\+switch-master $namePattern $initialPattern 6379 $promotedPattern 6379(?:\s|$)") { $kind = "Promotion" }
        if ($kind) {
            $timestamp = [DateTimeOffset]::Parse($timestampMatch.Value)
            if (-not $events[$kind] -or $timestamp -lt $events[$kind]) { $events[$kind] = $timestamp }
        }
    }
    return [PSCustomObject]$events
}

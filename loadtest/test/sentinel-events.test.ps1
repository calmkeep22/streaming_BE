$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "../sentinel-events.ps1")

$lines = @(
    'sentinel-2 | 2026-01-01T00:00:05.200Z 1:X +sdown master streaming-master 172.29.0.10 6379',
    'sentinel-1 | 2026-01-01T00:00:04.000Z 1:X +sdown master streaming-master 172.29.0.10 6379',
    'sentinel-1 | 2026-01-01T00:00:04.100Z 1:X +odown master streaming-master 172.29.0.10 6379 #quorum 2/2',
    'sentinel-1 | 2026-01-01T00:00:06.000Z 1:X +switch-master streaming-master 172.29.0.10 6379 172.29.0.11 6379',
    'sentinel-3 | 2026-01-01T00:00:01.000Z 1:X +switch-master other-master 172.29.0.10 6379 172.29.0.11 6379',
    'sentinel-3 | 2026-01-01T00:00:02.000Z 1:X +sdown master streaming-master 172.29.0.11 6379',
    'sentinel-3 | 2026-01-01T00:00:03.000Z 1:X +switch-master streaming-master 172.29.0.11 6379 172.29.0.10 6379',
    'sentinel-3 | untimed +sdown master streaming-master 172.29.0.10 6379'
)
$result = Read-SentinelEvents -Lines $lines -MasterName 'streaming-master' -InitialHost '172.29.0.10' -PromotedHost '172.29.0.11'
if ($result.SubjectiveDown -ne [DateTimeOffset]::Parse('2026-01-01T00:00:04Z')) { throw 'Earliest SDOWN mismatch' }
if ($result.ObjectiveDown -ne [DateTimeOffset]::Parse('2026-01-01T00:00:04.100Z')) { throw 'ODOWN mismatch' }
if ($result.Promotion -ne [DateTimeOffset]::Parse('2026-01-01T00:00:06Z')) { throw 'Promotion mismatch' }
$missing = Read-SentinelEvents -Lines @() -MasterName 'streaming-master' -InitialHost '172.29.0.10' -PromotedHost '172.29.0.11'
if ($missing.SubjectiveDown -or $missing.ObjectiveDown -or $missing.Promotion) { throw 'Missing events must remain unavailable' }
'Sentinel event parser tests passed'
